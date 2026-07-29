package mcbot.client.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import mcbot.client.BotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds items anywhere in the player's inventory and gets them into the hand.
 *
 * <p>Restricting the bot to the nine hotbar slots meant it would announce it was out of building
 * blocks while holding a stack two rows up. This class searches all 36 slots and, when the item it
 * wants is not already on the hotbar, swaps it down using the same container interaction the
 * inventory screen performs when you press a number key over a slot — no screen is opened and the
 * server sees an ordinary swap.</p>
 *
 * <p>Slot numbering is the one trap here. {@link Inventory} indices run 0-8 for the hotbar and 9-35
 * for the main inventory, but {@code InventoryMenu} puts the hotbar <em>last</em>: 9-35 main, then
 * 36-44 hotbar. {@link #menuSlot(int)} converts between the two.</p>
 */
public final class InventoryManager {

	/** Where the hotbar starts in {@code InventoryMenu}'s slot numbering. */
	private static final int MENU_HOTBAR_START = 36;

	/** Destroy speed of a bare hand. Only worth swapping to a tool that beats this. */
	private static final double BARE_HAND_SPEED = 1.0;

	/** A player's unarmed attack damage, the base the weapon's modifier applies on top of. */
	private static final double BASE_ATTACK_DAMAGE = 1.0;

	/** Solid full blocks that misbehave underfoot or when clicked. */
	private static final Set<Block> UNSUITABLE_BUILDING_BLOCKS = Set.of(
			Blocks.TNT,
			Blocks.SLIME_BLOCK,
			Blocks.HONEY_BLOCK,
			Blocks.ICE,
			Blocks.BLUE_ICE,
			Blocks.PACKED_ICE,
			Blocks.MAGMA_BLOCK,
			Blocks.SOUL_SAND,
			Blocks.OBSERVER);

	/** Edible, but with a cost: poison, hunger, or simply awful value. Last resort only. */
	private static final Set<Item> RISKY_FOODS = Set.of(
			Items.ROTTEN_FLESH,
			Items.SPIDER_EYE,
			Items.PUFFERFISH,
			Items.POISONOUS_POTATO,
			Items.SUSPICIOUS_STEW,
			Items.CHICKEN);

	/** Kept back for healing rather than spent on ordinary hunger. */
	private static final Set<Item> EMERGENCY_FOODS = Set.of(
			Items.GOLDEN_APPLE,
			Items.ENCHANTED_GOLDEN_APPLE);

	private InventoryManager() {
	}

	// ---------------------------------------------------------------- queries

	/** Every stack in the main inventory and hotbar, for cost estimation inside the pathfinder. */
	public static List<ItemStack> snapshot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}
		return stacks;
	}

	public static boolean has(LocalPlayer player, Predicate<ItemStack> test) {
		return findSlot(player, test) >= 0;
	}

	/** First inventory slot whose stack matches, or {@code -1}. */
	public static int findSlot(LocalPlayer player, Predicate<ItemStack> test) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && test.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- equipping

	/** Puts a matching item in the main hand. @return false when the player has none */
	public static boolean equip(Minecraft minecraft, LocalPlayer player, Predicate<ItemStack> test) {
		return select(minecraft, player, findSlot(player, test));
	}

	/**
	 * Puts the highest-scoring item in the main hand, ignoring anything scoring at or below
	 * {@code minimumScore}.
	 */
	public static boolean equipBest(Minecraft minecraft, LocalPlayer player,
			ToDoubleFunction<ItemStack> score, double minimumScore) {
		Inventory inventory = player.getInventory();
		int bestSlot = -1;
		double bestScore = minimumScore;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			double value = score.applyAsDouble(stack);
			if (value > bestScore) {
				bestScore = value;
				bestSlot = slot;
			}
		}
		return select(minecraft, player, bestSlot);
	}

	/** Equips the fastest tool for this block, leaving the current item if nothing beats a hand. */
	public static boolean equipBestTool(Minecraft minecraft, LocalPlayer player, BlockState state) {
		// The small bonus keeps a correct tool ahead of an equally fast wrong one, so blocks drop.
		return equipBest(minecraft, player,
				stack -> stack.getDestroySpeed(state) + (stack.isCorrectToolForDrops(state) ? 0.5 : 0.0),
				BARE_HAND_SPEED);
	}

	public static boolean equipBuildingBlock(Minecraft minecraft, LocalPlayer player) {
		return equipBuildingBlock(minecraft, player, null);
	}

	/**
	 * Equips something to build with, refusing to spend {@code excluded}.
	 *
	 * <p>The exclusion is the block the bot is out here collecting. Pillaring with the diamond ore
	 * you came for defeats the point of the trip, so the haul is protected and the bot goes and
	 * digs something worthless instead.</p>
	 *
	 * <p>Reporting no material — rather than quietly spending the haul — is what triggers that: the
	 * caller responds by gathering scaffolding.</p>
	 */
	public static boolean equipBuildingBlock(Minecraft minecraft, LocalPlayer player, Block excluded) {
		return equip(minecraft, player, scaffoldFilter(excluded));
	}

	/**
	 * What the bot is willing to spend on the scaffolding it places to get somewhere.
	 *
	 * <p>Every route-placing path runs through here, which is what makes {@code scaffoldBlock} a
	 * single honest answer to "what will this cost me" rather than a hint some call sites respect.</p>
	 *
	 * <p>When a block is named the filter is exactly that block — no fallback. Falling back would
	 * defeat the point: someone who says "bridge with cobblestone" is saying which stack they are
	 * willing to lose, and spending the diamonds once the cobble runs out is the outcome they were
	 * guarding against. Running dry instead surfaces as {@code NO_MATERIAL}, which the controller
	 * already handles by routing around.</p>
	 *
	 * <p>A named block also overrides {@code excluded}. Naming the very block being collected is
	 * unusual, but it is unambiguous — someone who says "bridge with oak_log" while gathering oak logs
	 * has said what they want, and second-guessing an explicit instruction with an implicit rule is
	 * how a bot ends up refusing to move for reasons nobody can see.</p>
	 *
	 * @param excluded the haul the bot is out collecting, never spent, or {@code null} for none
	 */
	public static Predicate<ItemStack> scaffoldFilter(Block excluded) {
		Item chosen = scaffoldItem();
		return chosen != null
				? stack -> stack.is(chosen)
				: stack -> isBuildingBlockExcept(stack, excluded);
	}

	/**
	 * The block {@code scaffoldBlock} names, or {@code null} for "anything spare".
	 *
	 * <p>Also {@code null} when the setting holds something unrecognised. That is deliberate: a typo
	 * should degrade to the old behaviour rather than leave the bot unable to place anything at all,
	 * and the setting is validated where it is written, so a bad value cannot arrive here quietly.</p>
	 */
	public static Item scaffoldItem() {
		String name = BotSettings.SCAFFOLD_BLOCK.get();
		if (name.equalsIgnoreCase(BotSettings.ANY_SCAFFOLD)) {
			return null;
		}
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == Items.AIR ? null : item;
	}

	/** What to call the scaffolding in a message — the chosen block, or the generic description. */
	public static String scaffoldName() {
		Item chosen = scaffoldItem();
		return chosen == null
				? "building blocks"
				: chosen.getName(chosen.getDefaultInstance()).getString();
	}

	/** A building block we are willing to spend — solid, well-behaved, and not the protected haul. */
	public static boolean isBuildingBlockExcept(ItemStack stack, Block excluded) {
		return isBuildingBlock(stack) && !isBlock(stack, excluded);
	}

	private static boolean isBlock(ItemStack stack, Block block) {
		return block != null
				&& stack.getItem() instanceof BlockItem blockItem
				&& blockItem.getBlock() == block;
	}

	public static boolean hasBuildingBlock(LocalPlayer player) {
		return hasBuildingBlock(player, null);
	}

	/** Whether anything is carried that we are willing to build with, ignoring {@code excluded}. */
	public static boolean hasBuildingBlock(LocalPlayer player, Block excluded) {
		return has(player, scaffoldFilter(excluded));
	}

	private static double scoreFood(ItemStack stack, int missingHunger, boolean allowRisky) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) {
			return Double.NEGATIVE_INFINITY;
		}
		Item item = stack.getItem();
		if (EMERGENCY_FOODS.contains(item)) {
			return Double.NEGATIVE_INFINITY; // saved for healing, never for a snack
		}
		if (!allowRisky && RISKY_FOODS.contains(item)) {
			return Double.NEGATIVE_INFINITY;
		}
		// Judge food by what it actually delivers: hunger points *plus saturation*, which is what
		// stops the bar emptying again a minute later. Cooked food beats raw by a wide margin on
		// that second term — steak gives 8 hunger and 12.8 saturation against raw beef's 3 and 1.8.
		double value = food.nutrition() + food.saturation();

		// Overshooting the gap wastes the surplus, but only mildly. Weighting this heavily is what
		// made the bot pick raw beef over steak: with a small gap to fill, the penalty on the
		// larger meal outweighed the fact that it was several times better food.
		int waste = Math.max(0, food.nutrition() - missingHunger);
		return value - waste * 0.5;
	}

	/**
	 * Damage per second, rather than damage per hit.
	 *
	 * <p>This is what makes the bot pick a sword over an axe without any special-casing. An axe
	 * hits harder — a diamond axe does 9 to a diamond sword's 7 — but swings far slower, so the
	 * sword wins comfortably on sustained damage. Scoring by hit damage alone picks the axe, which
	 * is the wrong weapon for a fight the bot cannot retreat from.</p>
	 *
	 * <p>Reading both attributes rather than checking item classes also means modded weapons are
	 * ranked correctly for free.</p>
	 */
	private static double weaponScore(ItemStack stack) {
		ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers == null) {
			return 0.0;
		}
		double damage = modifiers.compute(
				Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
		double speed = modifiers.compute(
				Attributes.ATTACK_SPEED, Attributes.DEFAULT_ATTACK_SPEED, EquipmentSlot.MAINHAND);
		return damage * Math.max(speed, 0.1);
	}


	// ---------------------------------------------------------------- predicates

	/**
	 * Whether this stack is safe to build with: a solid full block that will not fall away, open a
	 * screen when clicked, or otherwise misbehave underfoot.
	 */
	@SuppressWarnings("deprecation") // isSolid(): no level/pos available for the precise variant
	public static boolean isBuildingBlock(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		if (block instanceof FallingBlock || block instanceof EntityBlock) {
			return false;
		}
		if (UNSUITABLE_BUILDING_BLOCKS.contains(block)) {
			return false;
		}
		return block.defaultBlockState().isSolid();
	}

	// ---------------------------------------------------------------- internals

	/**
	 * Selects the given inventory slot, swapping it onto the hotbar first if necessary.
	 *
	 * @param inventorySlot an {@link Inventory} index, or {@code -1} for "nothing found"
	 * @return whether the item is now in the main hand
	 */
	private static boolean select(Minecraft minecraft, LocalPlayer player, int inventorySlot) {
		if (inventorySlot < 0) {
			return false;
		}
		Inventory inventory = player.getInventory();

		if (inventorySlot < Inventory.SELECTION_SIZE) {
			if (inventory.getSelectedSlot() != inventorySlot) {
				inventory.setSelectedSlot(inventorySlot);
			}
			return true;
		}

		// The item is in the main inventory. Swap it down to a hotbar slot, exactly as pressing a
		// number key over that slot in the inventory screen would.
		if (minecraft.gameMode == null || minecraft.gameMode.isServerControlledInventory()) {
			return false;
		}
		if (player.containerMenu != player.inventoryMenu) {
			return false; // another container is open; leave its slots alone
		}

		int hotbarSlot = inventory.getSuitableHotbarSlot();
		minecraft.gameMode.handleContainerInput(
				player.inventoryMenu.containerId,
				menuSlot(inventorySlot),
				hotbarSlot,
				ContainerInput.SWAP,
				player);
		inventory.setSelectedSlot(hotbarSlot);
		return true;
	}

	/** Converts an {@link Inventory} index to the matching {@code InventoryMenu} slot index. */
	private static int menuSlot(int inventorySlot) {
		return inventorySlot < Inventory.SELECTION_SIZE
				? MENU_HOTBAR_START + inventorySlot
				: inventorySlot;
	}

	public static boolean equipFood(Minecraft minecraft, LocalPlayer player) {
		return equip(minecraft, player, InventoryManager::isFood);
	}

	public static boolean hasFood(LocalPlayer player) {
		return has(player, InventoryManager::isFood);
	}

	public static boolean isFood(ItemStack stack) {
		return stack.has(DataComponents.FOOD);
	}

	public static boolean isEmergencyFood(ItemStack stack) {
		return EMERGENCY_FOODS.contains(stack.getItem());
	}

	/**
	 * Equips the most sensible food for the hunger actually missing.
	 *
	 * <p>Grabbing the first edible thing in the inventory is how a bot ends up eating a golden
	 * apple to top up one hunger point, or poisoning itself on rotten flesh while carrying bread.
	 * This prefers the food that fills the gap with least waste, keeps golden apples back for
	 * emergency healing, and only falls back to the risky stuff when there is nothing else.</p>
	 *
	 * @param missingHunger hunger points below full, used to avoid overeating good food
	 */
	public static boolean equipBestFood(Minecraft minecraft, LocalPlayer player, int missingHunger) {
		if (equipBest(minecraft, player,
				stack -> scoreFood(stack, missingHunger, false), Double.NEGATIVE_INFINITY)) {
			return true;
		}
		// Nothing wholesome left — rotten flesh beats starving.
		return equipBest(minecraft, player,
				stack -> scoreFood(stack, missingHunger, true), Double.NEGATIVE_INFINITY);
	}

	/** Equips a golden apple or similar, for when health matters more than the hunger bar. */
	public static boolean equipEmergencyFood(Minecraft minecraft, LocalPlayer player) {
		return equip(minecraft, player, stack -> EMERGENCY_FOODS.contains(stack.getItem()));
	}

	public static boolean hasEmergencyFood(LocalPlayer player) {
		return has(player, stack -> EMERGENCY_FOODS.contains(stack.getItem()));
	}

	/** Equips the best weapon available, leaving the hand alone if nothing beats a fist. */
	public static boolean equipBestWeapon(Minecraft minecraft, LocalPlayer player) {
		return equipBest(minecraft, player, InventoryManager::weaponScore, 1.0);
	}

	/**
	 * Whether the player carries food it would actually spend on ordinary hunger.
	 *
	 * <p>Distinct from {@link #hasFood}: a golden apple satisfies {@code hasFood} but is held back
	 * for emergencies, so a bot carrying <em>only</em> golden apples would forever decide it should
	 * eat and then refuse to, standing still. This is what {@code shouldEat} must check.</p>
	 */
	public static boolean hasEdibleFood(LocalPlayer player) {
		return has(player, stack -> isFood(stack) && !isEmergencyFood(stack));
	}

	/**
	 * Whether this stack is worth banking in a chest rather than carrying on with.
	 *
	 * <p>The keep-list is everything the bot needs to keep <em>working</em>: tools and weapons, food,
	 * and scaffolding to bridge with. Everything else is haul. Getting this wrong in the generous
	 * direction is the expensive mistake — deposit the pickaxe and the mining run is over, whereas
	 * keeping a few stacks of cobble merely wastes some space.</p>
	 *
	 * <p>A water bucket is kept too, though the bot no longer has any use for one itself. It is a
	 * utility a player carries on purpose, and quietly banking it would be a worse surprise than the
	 * slot is worth.</p>
	 */
	public static boolean isHaul(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (isFood(stack) || isBuildingBlock(stack) || stack.is(Items.WATER_BUCKET)) {
			return false;
		}
		return !stack.isDamageableItem(); // tools and weapons are the damageable things we carry
	}

	/** Fraction of the main inventory currently occupied, ignoring armour and the off-hand. */
	public static double fullness(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int used = 0;
		int total = 0;
		for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
			total++;
			if (!inventory.getItem(slot).isEmpty()) {
				used++;
			}
		}
		return total == 0 ? 0.0 : (double) used / total;
	}

	/** Whether the bot is carrying anything worth a trip to a chest. */
	public static boolean hasHaul(LocalPlayer player) {
		return has(player, InventoryManager::isHaul);
	}

	// ---------------------------------------------------------------- wearing

	/**
	 * The four armour slots, helmet first.
	 *
	 * <p>Ordered the way the inventory screen draws them, so anything that walks the list reads in the
	 * order a person would expect to be told about it.</p>
	 */
	public static final List<EquipmentSlot> ARMOUR_SLOTS =
			List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	/**
	 * Where this item belongs when worn, or {@code null} if it is not wearable at all.
	 *
	 * <p>Read from the item's own {@code EQUIPPABLE} component rather than from a list of armour
	 * items, so a carved pumpkin, an elytra and a mob head are all handled without being named — and so
	 * are whatever else future versions decide can be worn.</p>
	 */
	public static EquipmentSlot wearSlot(ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable == null ? null : equippable.slot();
	}

	/** What is worn in each slot, empty stacks included, helmet first. */
	public static Map<EquipmentSlot, ItemStack> worn(LocalPlayer player) {
		Map<EquipmentSlot, ItemStack> equipment = new LinkedHashMap<>();
		for (EquipmentSlot slot : ARMOUR_SLOTS) {
			equipment.put(slot, player.getItemBySlot(slot));
		}
		equipment.put(EquipmentSlot.OFFHAND, player.getItemBySlot(EquipmentSlot.OFFHAND));
		return equipment;
	}

	/**
	 * Puts a specific item on, in whichever equipment slot is asked for.
	 *
	 * @param slot where it should end up: an armour slot, or {@link EquipmentSlot#OFFHAND}
	 * @return whether it is now there
	 */
	public static boolean wearItem(Minecraft minecraft, LocalPlayer player, Item item,
			EquipmentSlot slot) {
		return moveToEquipment(minecraft, player, findSlot(player, stack -> stack.is(item)), slot);
	}

	/**
	 * Puts on the best armour carried for one slot, if it beats what is already there.
	 *
	 * @return the piece now worn, or an empty stack when nothing was worth changing to
	 */
	public static ItemStack wearBestArmour(Minecraft minecraft, LocalPlayer player,
			EquipmentSlot slot) {
		Inventory inventory = player.getInventory();
		int bestSlot = -1;
		// Starting from what is already on means an equal piece is left alone. Swapping like for like
		// would be three clicks, a durability-neutral shuffle, and a line of chat saying nothing.
		double bestScore = armourScore(player.getItemBySlot(slot), slot);

		for (int candidate = 0; candidate < inventory.getContainerSize(); candidate++) {
			double score = armourScore(inventory.getItem(candidate), slot);
			if (score > bestScore) {
				bestScore = score;
				bestSlot = candidate;
			}
		}

		if (bestSlot < 0) {
			return ItemStack.EMPTY;
		}
		ItemStack chosen = inventory.getItem(bestSlot).copy();
		return moveToEquipment(minecraft, player, bestSlot, slot) ? chosen : ItemStack.EMPTY;
	}

	/**
	 * How good a piece of armour is for a slot, as a number only good for ranking.
	 *
	 * <p>Armour points plus toughness, read off the item's own attributes for the same reason
	 * {@link #weaponScore} does: netherite and diamond give the same armour and differ only in
	 * toughness, so leaving it out would call them equal and never upgrade. Zero for anything that is
	 * not armour for this slot at all, which is what keeps a carved pumpkin off the bot's head.</p>
	 */
	private static double armourScore(ItemStack stack, EquipmentSlot slot) {
		if (stack.isEmpty() || wearSlot(stack) != slot) {
			return 0.0;
		}
		ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers == null) {
			return 0.0;
		}
		return modifiers.compute(Attributes.ARMOR, 0.0, slot)
				+ modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
	}

	/**
	 * Moves an inventory slot's contents onto the body, swapping out whatever was there.
	 *
	 * <p>Three ordinary clicks, exactly as a person makes them: take the item, put it on and pick up
	 * what it displaced, then drop that into the slot the new piece came from. The cursor is always
	 * empty afterwards, in all three of the cases that can arise — the equipment slot was empty, it
	 * held something, or it refused the item entirely. That last one is why this is safe: an armour
	 * slot will not accept a piece that does not belong in it, so a mistake about which slot is which
	 * ends with the item back where it started rather than a boot on the bot's head.</p>
	 *
	 * @param inventorySlot an {@link Inventory} index, or {@code -1} for "nothing found"
	 */
	private static boolean moveToEquipment(Minecraft minecraft, LocalPlayer player, int inventorySlot,
			EquipmentSlot slot) {
		int target = equipmentMenuSlot(slot);
		if (inventorySlot < 0 || target < 0) {
			return false;
		}
		if (minecraft.gameMode == null || minecraft.gameMode.isServerControlledInventory()) {
			return false;
		}
		if (player.containerMenu != player.inventoryMenu) {
			return false; // another container is open; leave its slots alone
		}

		Item wanted = player.getInventory().getItem(inventorySlot).getItem();
		int source = menuSlot(inventorySlot);
		click(minecraft, player, source);
		click(minecraft, player, target);
		click(minecraft, player, source);

		// Checked rather than assumed. The click is applied to the menu here and now, so this reads the
		// real outcome — and an action that reports success while the armour sits in a pocket is worse
		// than one that admits it failed.
		return player.getItemBySlot(slot).is(wanted);
	}

	/** An ordinary left click on one menu slot. */
	private static void click(Minecraft minecraft, LocalPlayer player, int menuSlot) {
		minecraft.gameMode.handleContainerInput(
				player.inventoryMenu.containerId, menuSlot, 0, ContainerInput.PICKUP, player);
	}

	/**
	 * Where a piece of equipment sits in the player's own inventory menu.
	 *
	 * <p>Written out rather than derived from {@link EquipmentSlot#getIndex()}, which numbers armour
	 * from the boots upwards while the menu lists it from the helmet down. Deriving one from the other
	 * is an inversion that reads as correct and is wrong in the game.</p>
	 *
	 * @return {@code -1} for the slots a player has no square for: the main hand, and the body and
	 *         saddle slots that belong to animals
	 */
	private static int equipmentMenuSlot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> InventoryMenu.ARMOR_SLOT_START;
			case CHEST -> InventoryMenu.ARMOR_SLOT_START + 1;
			case LEGS -> InventoryMenu.ARMOR_SLOT_START + 2;
			case FEET -> InventoryMenu.ARMOR_SLOT_START + 3;
			case OFFHAND -> InventoryMenu.SHIELD_SLOT;
			case MAINHAND, BODY, SADDLE -> -1;
		};
	}

	// ---------------------------------------------------------------- wear and tear

	/**
	 * Everything in use that is close to breaking, held and worn.
	 *
	 * <p>Only what is <em>equipped</em>. A spare pickaxe rotting in the pack is not a problem yet, and
	 * a warning about it is one the reader cannot act on — whereas the tool actually in the hand is
	 * about to disappear mid-job, which is the moment before every avoidable disaster the bot has.</p>
	 *
	 * @param percent the share of durability at or below which something counts as nearly worn out
	 */
	public static List<ItemStack> nearlyBroken(LocalPlayer player, int percent) {
		List<ItemStack> failing = new ArrayList<>();
		consider(failing, player.getMainHandItem(), percent);
		for (ItemStack stack : worn(player).values()) {
			consider(failing, stack, percent);
		}
		return failing;
	}

	/** Uses left in something damageable, or {@code -1} when it cannot break. */
	public static int usesLeft(ItemStack stack) {
		return stack.isEmpty() || !stack.isDamageableItem()
				? -1
				: stack.getMaxDamage() - stack.getDamageValue();
	}

	private static void consider(List<ItemStack> failing, ItemStack stack, int percent) {
		int left = usesLeft(stack);
		// Multiplied out rather than divided, so a tool with an odd maximum does not round its way past
		// the threshold and get missed.
		if (left >= 0 && left * 100 <= stack.getMaxDamage() * percent) {
			failing.add(stack);
		}
	}

	// ---------------------------------------------------------------- naming things

	/** Puts a specific item in the main hand. @return false when the player has none */
	public static boolean equipItem(Minecraft minecraft, LocalPlayer player, Item item) {
		return equip(minecraft, player, stack -> stack.is(item));
	}

	/** How many of an item are carried, counting across stacks. */
	public static int count(LocalPlayer player, Item item) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Everything carried, tallied by item and ordered most-numerous first.
	 *
	 * <p>Ordered rather than alphabetical because the reader — increasingly a model deciding what to do
	 * next — cares far more about the two hundred cobblestone than about a single stray sapling, and a
	 * long list gets skimmed from the top.</p>
	 */
	public static Map<Item, Integer> contents(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		Map<Item, Integer> tally = new HashMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				tally.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return tally.entrySet().stream()
				.sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
						(a, b) -> a, LinkedHashMap::new));
	}

	/**
	 * Throws items on the ground.
	 *
	 * <p>Done by clicking the slots rather than by equipping and pressing Q, because a throw is one
	 * container input per stack and needs no hand: equipping would mean changing the held item for
	 * every stack, which cannot be done more than once in a tick without the server losing track.</p>
	 *
	 * @param wanted how many to throw, or {@link Integer#MAX_VALUE} for all of them
	 * @return how many were actually thrown
	 */
	public static int dropItem(Minecraft minecraft, LocalPlayer player, Item item, int wanted) {
		if (minecraft.gameMode == null || wanted <= 0) {
			return 0;
		}
		int thrown = 0;
		for (Slot slot : player.inventoryMenu.slots) {
			if (thrown >= wanted || !(slot.container instanceof Inventory)) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (!stack.is(item)) {
				continue;
			}
			int remaining = wanted - thrown;
			if (stack.getCount() <= remaining) {
				// Button 1 throws the whole stack; 0 throws one item at a time.
				minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot.index,
						1, ContainerInput.THROW, player);
				thrown += stack.getCount();
			} else {
				for (int i = 0; i < remaining; i++) {
					minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot.index,
							0, ContainerInput.THROW, player);
				}
				thrown += remaining;
			}
		}
		return thrown;
	}
}

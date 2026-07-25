package mcbot.client.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
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
		return equip(minecraft, player,
				stack -> isBuildingBlock(stack) && !isBlock(stack, excluded));
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
		return has(player, stack -> isBuildingBlock(stack) && !isBlock(stack, excluded));
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
}

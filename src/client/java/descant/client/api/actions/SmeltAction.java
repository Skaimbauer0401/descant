package descant.client.api.actions;

import java.util.List;

import descant.client.BotSettings;
import descant.client.action.Smelter;
import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.inventory.InventoryManager;
import descant.client.inventory.RecipeFinder;
import descant.client.path.BlockSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Smelts something in a furnace.
 *
 * <p>The bot finds a furnace, walks to it, loads it, waits, and takes out both the result and
 * whatever was not used. The count is honoured at the collecting end rather than the loading end —
 * see {@link Smelter} — so a request for three out of a stack of thirty gets three, and the
 * twenty-seven come home again.</p>
 *
 * <p>Fuel is chosen rather than demanded, because being made to name it would mean calling
 * {@code inventory} first every single time to find out what is available. Coal and charcoal are
 * preferred, and tools, armour and lava buckets are never burnt — the ways of losing something
 * valuable to a furnace are few and worth ruling out by hand.</p>
 */
public final class SmeltAction implements Action {

	/** One furnace load. More than this would need reloading, which is a second trip's work. */
	private static final int MAX_PER_RUN = 64;

	@Override
	public String name() {
		return "smelt";
	}

	@Override
	public String description() {
		return "Smelt items in a furnace: ore into ingots, food into cooked food, sand into glass. The "
				+ "bot finds a furnace, walks to it, loads the items and enough fuel, waits, and "
				+ "collects the result. Fuel is picked from the inventory automatically. Smelting takes "
				+ "about 10 seconds per item, so ask for what you need rather than everything.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("item", ParameterType.STRING,
						"The raw item id to smelt, such as 'raw_iron', 'sand' or 'beef'."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many to smelt. Defaults to everything carried, up to a furnace load of 64. "
								+ "Remember it takes about 10 seconds each."),
				Parameter.optional("fuel", ParameterType.STRING,
						"Which fuel to burn, such as 'coal'. Leave it out to let the bot choose."),
				Travel.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		LocalPlayer player = context.player();
		Minecraft minecraft = context.minecraft();

		String wanted = arguments.getString("item");
		Item input = ItemNames.resolve(wanted);
		if (input == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}

		int carried = InventoryManager.count(player, input);
		if (carried == 0) {
			return ActionResult.failed("No " + wanted + " to smelt. Call inventory to see what is carried.");
		}
		ItemStack result = RecipeFinder.smeltResultFor(player, input);
		if (result == null) {
			return ActionResult.failed(wanted + " cannot be smelted — a furnace does not take it.");
		}

		int count = Math.min(arguments.getInt("count", Math.min(carried, MAX_PER_RUN)), carried);
		if (count < 1) {
			return ActionResult.failed("count must be at least 1.");
		}
		if (count > MAX_PER_RUN) {
			return ActionResult.failed("A furnace holds " + MAX_PER_RUN + " at a time. Ask for that many "
					+ "or fewer, and repeat if more is needed.");
		}

		ItemStack fuel = chooseFuel(minecraft, player, arguments.getString("fuel", null));
		if (fuel == null) {
			return ActionResult.failed("Nothing to burn. Coal, charcoal or planks would do — gather some "
					+ "with find, or craft planks from logs.");
		}
		int pieces = Smelter.fuelNeeded(minecraft, fuel, count);
		if (InventoryManager.count(player, fuel.getItem()) < pieces) {
			return ActionResult.failed("Not enough fuel: smelting " + count + " " + wanted + " needs about "
					+ pieces + " " + InventoryAction.id(fuel.getItem()) + ".");
		}

		BlockPos furnace = findFurnace(context);
		if (furnace == null) {
			return ActionResult.failed("No furnace within " + BotSettings.CHEST_SEARCH_RADIUS.get()
					+ " blocks. Craft one with craft(item=furnace) and place it, or travel to one.");
		}

		context.controller().smelt(minecraft, player, furnace, input, fuel.getItem(), count, wanted,
				Travel.mode(arguments));
		return ActionResult.okQuiet("Smelting " + count + " " + wanted + " into "
				+ InventoryAction.id(result.getItem()) + ", burning "
				+ pieces + " " + InventoryAction.id(fuel.getItem()) + ".");
	}

	/**
	 * Picks what to burn.
	 *
	 * <p>Coal first, then charcoal, then whatever else will burn — but never anything damageable and
	 * never a lava bucket. A furnace will happily eat a diamond hoe or a bucket of lava, and neither
	 * is a mistake anyone wants a bot making unattended.</p>
	 */
	private static ItemStack chooseFuel(Minecraft minecraft, LocalPlayer player, String named) {
		if (named != null) {
			Item item = ItemNames.resolve(named);
			if (item == null) {
				return null;
			}
			ItemStack held = new ItemStack(item);
			return minecraft.level.fuelValues().isFuel(held) && InventoryManager.count(player, item) > 0
					? held
					: null;
		}

		ItemStack best = null;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !minecraft.level.fuelValues().isFuel(stack)
					|| stack.isDamageableItem() || stack.is(Items.LAVA_BUCKET)) {
				continue;
			}
			if (stack.is(Items.COAL)) {
				return stack; // the obvious answer; stop looking
			}
			if (best == null || stack.is(Items.CHARCOAL)) {
				best = stack;
			}
		}
		return best;
	}

	/**
	 * The nearest furnace of any kind.
	 *
	 * <p>Blast furnaces and smokers are accepted because they open the same menu and the loading works
	 * identically — but a plain furnace is preferred, since it is the only one that smelts everything.
	 * A smoker will not take iron.</p>
	 */
	private static BlockPos findFurnace(ActionContext context) {
		BlockPos from = BlockPos.containing(context.player().position());
		int radius = BotSettings.CHEST_SEARCH_RADIUS.get();

		BlockPos plain = BlockSearcher.findNearest(context.minecraft().level, from, radius,
				state -> state.is(Blocks.FURNACE));
		if (plain != null) {
			return plain;
		}
		return BlockSearcher.findNearest(context.minecraft().level, from, radius,
				SmeltAction::isSpecialFurnace);
	}

	private static boolean isSpecialFurnace(BlockState state) {
		return state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER);
	}
}

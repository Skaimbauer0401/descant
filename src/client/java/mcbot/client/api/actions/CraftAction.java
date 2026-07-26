package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.inventory.RecipeFinder;
import mcbot.client.path.BlockSearcher;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

/**
 * Makes something, in the inventory's 2x2 grid or at a crafting table.
 *
 * <p>One action for both, because which grid a recipe needs is a property of the recipe and not a
 * decision worth passing up to the caller. Planks and sticks happen where the bot stands; anything
 * wider sends it to the nearest bench first.</p>
 *
 * <p>Only recipes the player has <b>unlocked</b> can be made — everything comes from the client's
 * recipe book, exactly as if a person were clicking it.</p>
 */
public final class CraftAction implements Action {

	@Override
	public String name() {
		return "craft";
	}

	@Override
	public String description() {
		return "Make an item from ingredients already carried. Small recipes are made on the spot; "
				+ "ones needing a 3x3 grid send the bot to the nearest crafting table automatically. "
				+ "Only recipes the player has unlocked can be made, and only from what is in the "
				+ "inventory — check with 'inventory' first if unsure. This does not smelt: use a "
				+ "furnace for that.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("item", ParameterType.STRING,
						"The item id to make, such as 'crafting_table', 'stick' or 'furnace'."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many times to run the recipe. Default 1. Note a recipe often yields "
								+ "several — one stick recipe makes four sticks."),
				Travel.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("item");
		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}
		int count = arguments.getInt("count", 1);
		if (count < 1) {
			return ActionResult.failed("count must be at least 1.");
		}

		LocalPlayer player = context.player();
		List<RecipeFinder.Match> recipes = RecipeFinder.forItem(player, item);
		if (recipes.isEmpty()) {
			return ActionResult.failed(noRecipe(player, item, wanted));
		}

		// Sorted craftable-first, so the head of the list is the one to use if any of them work.
		RecipeFinder.Match recipe = recipes.get(0);
		if (!recipe.craftable()) {
			return ActionResult.failed("Not enough ingredients to craft " + wanted
					+ ". Call inventory to see what is carried, then gather what is missing.");
		}

		BlockPos table = null;
		if (recipe.needsTable()) {
			table = BlockSearcher.findNearest(context.minecraft().level,
					BlockPos.containing(player.position()),
					BotSettings.CHEST_SEARCH_RADIUS.get(),
					state -> state.is(Blocks.CRAFTING_TABLE));
			if (table == null) {
				// The bench itself is a 2x2 recipe, so this is a solvable problem and worth saying how.
				return ActionResult.failed(wanted + " needs a 3x3 grid and there is no crafting table "
						+ "within " + BotSettings.CHEST_SEARCH_RADIUS.get() + " blocks. Craft one with "
						+ "craft(item=crafting_table) and place it, or go to an existing one.");
			}
		}

		context.controller().craft(context.minecraft(), player, recipe.id(), table, count, wanted,
				Travel.mode(arguments));
		int perCraft = recipe.result().getCount();
		return ActionResult.okQuiet("Crafting " + wanted + " x" + (count * perCraft)
				+ (table == null ? " here." : " at the crafting table."));
	}

	/**
	 * Why nothing can be made, as specifically as the recipe book allows.
	 *
	 * <p>Worth the distinction: "that is smelted, not crafted" tells a model to go and use a furnace,
	 * whereas a flat "no recipe" invites it to try the same call again.</p>
	 */
	private static String noRecipe(LocalPlayer player, Item item, String wanted) {
		if (RecipeFinder.isSmeltedNotCrafted(player, item)) {
			return wanted + " is smelted, not crafted — it needs a furnace, which the bot cannot "
					+ "operate yet.";
		}
		return "No crafting recipe for " + wanted + " is available. Either it is not craftable, or the "
				+ "player has not unlocked the recipe yet.";
	}
}

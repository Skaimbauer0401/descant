package descant.client.api.actions;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.inventory.InventoryManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Fetches things back out of the chest — the mirror of {@code deposit}.
 *
 * <p>Storing things is only half of having a base. The other half is going and getting them again:
 * the coal for a smelt, the planks for a build, the food for a long trip. Without this the chest is
 * somewhere things go and never come back from.</p>
 *
 * <p>Unlike every other action here, nothing can be checked before setting off — what is in a chest
 * is unknown until it is open. So this always goes, and coming back empty is a legitimate answer
 * rather than a failure.</p>
 */
public final class TakeAction implements Action {

	/** Everything in the chest. */
	private static final String ALL = "all";

	/** Anything edible. */
	private static final String FOOD = "food";

	@Override
	public String name() {
		return "take";
	}

	@Override
	public String description() {
		return "Fetch items out of the chest set with 'chest' and bring them back, then pick up "
				+ "whatever the bot was doing. Name an item id, or 'food' for anything edible, or "
				+ "'all' to empty the chest. Give a count to stop once the bot has that many. What is "
				+ "in the chest cannot be known until the bot gets there, so this may come back with "
				+ "less than asked for, or nothing.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("what", ParameterType.STRING,
						"What to fetch: an item id such as 'coal', or 'food' for anything edible, or "
								+ "'all' to empty the chest."),
				Parameter.optional("count", ParameterType.INTEGER,
						"Stop once the bot holds this many. Items come across a stack at a time, so it "
								+ "may bring back more than asked. Leave it out to take everything "
								+ "matching."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String what = arguments.getString("what").toLowerCase(Locale.ROOT);
		int count = arguments.getInt("count", 0);
		if (count < 0) {
			return ActionResult.failed("count cannot be negative.");
		}

		Predicate<ItemStack> filter;
		String describe;
		switch (what) {
			case ALL -> {
				filter = stack -> !stack.isEmpty();
				describe = "everything";
			}
			case FOOD -> {
				filter = InventoryManager::isFood;
				describe = "food";
			}
			default -> {
				Item item = ItemNames.resolve(what);
				if (item == null) {
					return ActionResult.failed(ItemNames.unknown(what)
							+ " You can also pass 'food' or 'all'.");
				}
				filter = stack -> stack.is(item);
				describe = count > 0 ? count + " " + what : what;
			}
		}

		String problem = context.controller().withdrawNow(context.minecraft(), filter, describe, count);
		return problem == null
				? ActionResult.okQuiet("Going to the chest for " + describe + ".")
				: ActionResult.failed(problem);
	}
}

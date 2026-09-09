package descant.client.api.actions;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.inventory.InventoryManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Stashes things in the chest, on demand.
 *
 * <p>Left to itself the bot banks only its <em>haul</em>, keeping the tools, food and building blocks
 * it needs to carry on working — which is right for the automatic trip that fires mid-job, and wrong
 * the moment somebody asks for something specific. Being told to stash the food and having the bot
 * keep the food back would make the command useless for the one case it was asked for.</p>
 *
 * <p>So an ordered deposit says what to hand over, and is obeyed. The keep-list is a default, not a
 * rule.</p>
 */
public final class DepositAction implements Action {

	/** Everything the bot gathered, keeping the kit. What the automatic trip uses. */
	private static final String HAUL = "haul";

	/** Anything edible, including what the bot would otherwise eat. */
	private static final String FOOD = "food";

	/** The lot. Tools and all. */
	private static final String ALL = "all";

	@Override
	public String name() {
		return "deposit";
	}

	@Override
	public String description() {
		return "Walk to the chest set with 'chest' and stash things in it, then pick up whatever the "
				+ "bot was doing. By default it banks the haul and keeps its tools, food and building "
				+ "blocks. Pass 'what' to override that: 'food' stashes all food, 'all' stashes "
				+ "everything including tools, or name a single item id to stash just that.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.optional("what", descant.client.api.ParameterType.STRING,
				"What to stash: 'haul' (the default — gathered goods, keeping the kit), 'food' for "
						+ "all food, 'all' for everything including tools, or an item id such as "
						+ "'cobblestone' to stash only that. Note 'food' and 'all' will hand over what "
						+ "the bot eats, so it may go hungry afterwards."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String what = arguments.getString("what", HAUL).toLowerCase(Locale.ROOT);

		Predicate<ItemStack> filter;
		String describe;
		switch (what) {
			case HAUL -> {
				filter = InventoryManager::isHaul;
				describe = "the haul";
			}
			case FOOD -> {
				filter = InventoryManager::isFood;
				describe = "all food";
			}
			case ALL -> {
				filter = stack -> !stack.isEmpty();
				describe = "everything";
			}
			default -> {
				Item item = ItemNames.resolve(what);
				if (item == null) {
					return ActionResult.failed(ItemNames.unknown(what)
							+ " You can also pass 'haul', 'food' or 'all'.");
				}
				filter = stack -> stack.is(item);
				describe = "the " + what;
			}
		}

		String problem = context.controller().depositNow(
				context.minecraft(), context.player(), filter, describe);
		return problem == null
				? ActionResult.okQuiet("Heading to the chest with " + describe + ".")
				: ActionResult.failed(problem);
	}
}

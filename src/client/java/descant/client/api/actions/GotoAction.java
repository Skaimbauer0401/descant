package descant.client.api.actions;

import java.util.List;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.control.TravelMode;
import descant.client.path.goal.Goal;
import descant.client.path.goal.GoalBlock;
import descant.client.path.goal.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Travels to a place.
 *
 * <p>The height is optional, and leaving it out is usually right. Naming an exact Y a thousand
 * blocks away means guessing the terrain height there, and guessing wrong makes the bot tunnel down
 * to your number or pillar up to it on arrival — so with no Y the goal becomes the column, reached
 * at whatever height the ground turns out to be.</p>
 *
 * <p><b>Usually right, and wrong in the Nether</b>, which is what {@link #heightNote} exists to say.
 * The rule above assumes a column has one surface in it. A Nether column has several — a floor, a
 * lava sea, a bridge over it, a roof — and the goal is satisfied at any height in the shaft, so the
 * bot can arrive at those coordinates on a deck nobody meant and report success.</p>
 */
public final class GotoAction implements Action {

	@Override
	public String name() {
		return "goto";
	}

	@Override
	public String description() {
		return "Travel to a location, pathfinding around, over and through whatever is in the way. "
				+ "Leave out 'y' unless you specifically need a particular height — without it the bot "
				+ "arrives at ground level, which is almost always what you want for a journey. "
				+ "IN THE NETHER, PASS ALL THREE. There is no single ground level there — the same "
				+ "column crosses a floor, a lava sea and a roof — so without a height the bot can "
				+ "arrive at those coordinates on a deck you did not mean. "
				+ "By default it WALKS there and only starts mining and bridging if there turns out to "
				+ "be no way on foot, saying so when it does. Pass travel='walk' to forbid that "
				+ "outright, or travel='build' to let it dig from the start.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("x", ParameterType.INTEGER, "East-west coordinate of the destination."),
				Parameter.required("z", ParameterType.INTEGER, "North-south coordinate of the destination."),
				Parameter.optional("y", ParameterType.INTEGER,
						"Height. Omit to arrive at ground level, whatever that turns out to be — but "
								+ "give it in the Nether, where the same column has several floors in it."),
				Travel.PARAMETER,
				Scaffold.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		int x = arguments.getInt("x");
		int z = arguments.getInt("z");
		TravelMode mode = Travel.mode(arguments);

		ActionResult rejected = Scaffold.choose(arguments);
		if (rejected != null) {
			return rejected;
		}

		Goal goal;
		if (arguments.has("y")) {
			goal = new GoalBlock(new BlockPos(x, arguments.getInt("y"), z));
		} else {
			// The reference height only seeds the search; the goal is satisfied at any height in the
			// column, so starting from where we stand costs nothing if it turns out to be wrong.
			goal = new GoalXZ(x, z, (int) Math.floor(context.player().getY()));
		}

		context.controller().navigateTo(goal, mode);
		return ActionResult.ok("Heading to " + goal.describe() + "."
				+ heightNote(context, arguments) + Travel.note(mode));
	}

	/**
	 * Warns when a Nether journey was given no height.
	 *
	 * <p>Said rather than refused. The height there is often genuinely unknown — nobody can name the
	 * Y of a fortress they have not seen — and a journey to roughly the right place is worth more than
	 * no journey at all. What must not happen is the arrival being taken at face value, so the warning
	 * goes in the result the model reads, next to the coordinates it is about.</p>
	 */
	private static String heightNote(ActionContext context, Arguments arguments) {
		if (arguments.has("y") || context.minecraft().level == null
				|| context.minecraft().level.dimension() != Level.NETHER) {
			return "";
		}
		return " No height given, and in the Nether that column has several floors in it — the bot may"
				+ " arrive on the wrong one. Pass y as well when you know it.";
	}
}

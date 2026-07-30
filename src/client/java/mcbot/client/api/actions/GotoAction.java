package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.control.TravelMode;
import mcbot.client.path.goal.Goal;
import mcbot.client.path.goal.GoalBlock;
import mcbot.client.path.goal.GoalXZ;
import net.minecraft.core.BlockPos;

/**
 * Travels to a place.
 *
 * <p>The height is optional, and leaving it out is usually right. Naming an exact Y a thousand
 * blocks away means guessing the terrain height there, and guessing wrong makes the bot tunnel down
 * to your number or pillar up to it on arrival — so with no Y the goal becomes the column, reached
 * at whatever height the ground turns out to be.</p>
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
						"Height. Omit to arrive at ground level, whatever that turns out to be."),
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
		return ActionResult.ok("Heading to " + goal.describe() + "." + Travel.note(mode));
	}
}

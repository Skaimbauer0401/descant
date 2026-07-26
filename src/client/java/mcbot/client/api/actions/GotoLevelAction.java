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
import mcbot.client.path.goal.GoalYLevel;
import net.minecraft.core.BlockPos;

/**
 * Reaches a height, anywhere.
 *
 * <p>Separate from {@code goto} rather than being it with the horizontal arguments left off, because
 * they are different requests: one is "be at this place", the other "be at this depth, wherever
 * that is easiest". Digging down to Y-12 to look for diamonds is the second, and expressing it as a
 * coordinate would mean picking an X and Z that do not matter and then being held to them.</p>
 */
public final class GotoLevelAction implements Action {

	@Override
	public String name() {
		return "gotoLevel";
	}

	@Override
	public String description() {
		return "Reach a particular height, anywhere — digging down to it or climbing up to it from "
				+ "wherever the bot is. Use this for 'get down to diamond level', not for travelling. "
				+ "It looks for a way there on foot first — caves and hillsides often provide one — and "
				+ "falls back to tunnelling, saying so when it does. Since digging is usually the point "
				+ "here, travel='build' saves it the wasted look.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("y", ParameterType.INTEGER, "The height to reach."),
				Travel.PARAMETER,
				Scaffold.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		TravelMode mode = Travel.mode(arguments);
		BlockPos column = BlockPos.containing(context.player().position());
		Goal goal = new GoalYLevel(arguments.getInt("y"), column);

		ActionResult rejected = Scaffold.choose(arguments);
		if (rejected != null) {
			return rejected;
		}

		context.controller().navigateTo(goal, mode);
		return ActionResult.ok("Heading to " + goal.describe() + "." + Travel.note(mode));
	}
}

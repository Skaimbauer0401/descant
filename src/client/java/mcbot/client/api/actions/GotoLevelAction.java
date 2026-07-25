package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
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
				+ "wherever the bot is. Use this for 'get down to diamond level', not for travelling.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("y", ParameterType.INTEGER, "The height to reach."),
				Parameter.optional("build", ParameterType.BOOLEAN,
						"Whether the bot may mine and place blocks to get there. Default true. "
								+ "Digging down almost always needs this."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		boolean build = arguments.getBoolean("build", true);
		BlockPos column = BlockPos.containing(context.player().position());
		Goal goal = new GoalYLevel(arguments.getInt("y"), column);

		context.controller().navigateTo(goal, build, build);
		return ActionResult.ok("Heading to " + goal.describe()
				+ (build ? "." : " (movement only, nothing will be mined or placed)."));
	}
}

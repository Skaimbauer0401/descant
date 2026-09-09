package descant.client.api.actions;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;

/** Drops whatever the bot is doing and releases the controls. */
public final class StopAction implements Action {

	@Override
	public String name() {
		return "stop";
	}

	@Override
	public String description() {
		return "Stop immediately, abandoning the current journey, hunt or banking trip and handing the "
				+ "controls back to the player. Always safe to call.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		context.controller().stop(context.minecraft());
		return ActionResult.ok("Stopped.");
	}
}

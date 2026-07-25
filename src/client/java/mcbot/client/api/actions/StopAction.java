package mcbot.client.api.actions;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;

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

package mcbot.client.api.actions;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;

/**
 * Goes and banks the haul now, without waiting for the inventory to fill.
 *
 * <p>The automatic trip only fires partway through a gathering job. This is for the other times: the
 * job is finished and the goods should go away, or room is needed before starting something
 * else.</p>
 */
public final class DepositAction implements Action {

	@Override
	public String name() {
		return "deposit";
	}

	@Override
	public String description() {
		return "Walk to the chest set with 'chest' and empty out everything gathered, keeping tools, "
				+ "weapons, food and building blocks. Whatever the bot was doing is picked up again "
				+ "afterwards. Use this to bank a finished haul, or to make room before a big job.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String problem = context.controller().depositNow(context.minecraft(), context.player());
		return problem == null
				? ActionResult.okQuiet("Heading to the chest to bank the haul.")
				: ActionResult.failed(problem);
	}
}

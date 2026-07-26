package mcbot.client.api.actions;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.control.BotController;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Reports what the bot is doing and how it is faring.
 *
 * <p>Deliberately more than the old status line. For a person this answers "is it stuck?"; for a
 * model it is the only way to perceive anything at all, and a model that cannot tell it is on 3
 * hearts, or that its inventory is full, cannot decide to do anything about it. So health, hunger
 * and inventory go in alongside the route — they cost nothing to report and are exactly the facts
 * that should change the next decision.</p>
 */
public final class StatusAction implements Action {

	@Override
	public String name() {
		return "status";
	}

	@Override
	public String description() {
		return "Report what the bot is currently doing, where it is, its health and hunger, and how "
				+ "full its inventory is. Costs nothing and changes nothing — call it whenever you need "
				+ "to know how things stand before deciding what to do next.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		BotController controller = context.controller();
		LocalPlayer player = context.player();
		StringBuilder text = new StringBuilder();

		text.append(controller.status());
		BlockPos goal = controller.goal();
		if (goal != null) {
			text.append(" → ").append(goal.getX()).append(", ")
					.append(goal.getY()).append(", ").append(goal.getZ());
		}
		switch (controller.status()) {
			case PLANNING -> text.append(" (searched ").append(controller.searchedNodes()).append(" nodes)");
			case FOLLOWING, BREAKING, PLACING, MINING -> text.append(" (")
					.append(controller.remainingSteps()).append(" steps left)");
			default -> {
			}
		}

		String progress = controller.huntProgress();
		if (progress != null) {
			text.append(" [").append(progress).append("]");
		}

		text.append(" | at ").append((int) player.getX()).append(", ")
				.append((int) player.getY()).append(", ").append((int) player.getZ());
		text.append(" | health ").append(Math.round(player.getHealth())).append("/20");
		text.append(", hunger ").append(player.getFoodData().getFoodLevel()).append("/20");
		text.append(" | inventory ").append(Math.round(InventoryManager.fullness(player) * 100)).append("% full");

		// The full flag rides along with the chest because a completed action reports this whole line
		// back to the model, which is where it will notice that banking has stopped working and why.
		BlockPos chest = controller.depositChest();
		text.append(" | banking ").append(chest == null
				? "off"
				: "at " + chest.getX() + ", " + chest.getY() + ", " + chest.getZ()
						+ (controller.chestFull() ? " (FULL — no room for anything more)" : ""));

		return ActionResult.ok(text.toString());
	}
}

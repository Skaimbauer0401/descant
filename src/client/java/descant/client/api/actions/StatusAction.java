package descant.client.api.actions;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.control.BotController;
import descant.client.inventory.InventoryManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Reports what the bot is doing, where it is, and what it is up against.
 *
 * <p>Deliberately more than the old status line. For a person this answers "is it stuck?"; for a model
 * it is the closest thing to perception it has, and one that cannot tell it is on 3 hearts, or that
 * its inventory is full, cannot decide to do anything about it.</p>
 *
 * <p>The world half — dimension, biome, time, light, what is hostile nearby — is here rather than
 * only in {@code look} because this is the report that arrives <em>unasked</em>, both after every
 * action that moved the bot and every few rounds of the AI loop. A model that has to make a second
 * call to find out it is in the Nether at night is a model that will not make it, and will reason
 * about the overworld instead. {@code look} still says more; this says enough to not be wrong.</p>
 */
public final class StatusAction implements Action {

	@Override
	public String name() {
		return "status";
	}

	@Override
	public String description() {
		return "Report how things stand: what the bot is doing and how far along, where it is, the "
				+ "dimension and biome, the time of day and light, anything hostile nearby, its health "
				+ "and hunger, how full its inventory is, and where it is banking. Costs nothing and "
				+ "changes nothing. Call it whenever you are unsure of anything — whether the bot moved, "
				+ "whether something worked, what is around it — rather than assuming.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		BotController controller = context.controller();
		LocalPlayer player = context.player();
		ClientLevel level = context.minecraft().level;
		BlockPos feet = BlockPos.containing(player.position());
		StringBuilder text = new StringBuilder();

		// ---- what it is doing
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

		// ---- where it is
		text.append(" | at ").append((int) player.getX()).append(", ")
				.append((int) player.getY()).append(", ").append((int) player.getZ());
		text.append(" in ").append(Surroundings.dimension(level));
		text.append(", ").append(Surroundings.biome(level, feet));

		// ---- when it is, and how likely that is to spawn something on top of it
		text.append(" | ").append(Surroundings.clock(level));
		text.append(", light ").append(level.getMaxLocalRawBrightness(feet)).append("/15");
		text.append(" | threats: ").append(Surroundings.threats(level, player));

		// ---- how it is faring
		text.append(" | health ").append(Math.round(player.getHealth())).append("/20");
		text.append(", hunger ").append(player.getFoodData().getFoodLevel()).append("/20");
		// Not a footnote about speed. Below the sprinting threshold the bot cannot make the longer
		// jumps at all, so routes that existed a minute ago stop existing — which looks from outside
		// like the pathfinder having got worse, and is worth saying rather than leaving to be deduced.
		if (!BotController.canSprint(player)) {
			text.append(" (too low to sprint, so no long jumps)");
		}
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

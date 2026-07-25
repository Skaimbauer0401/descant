package mcbot.client.api.actions;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import mcbot.client.BotSettings;
import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.inventory.ChestSource;
import mcbot.client.settings.SettingChoice;
import net.minecraft.core.BlockPos;

/**
 * Chooses the container the bot banks its haul in — or turns banking off.
 *
 * <p>The container is resolved once, here, and remembered as a position. The alternative would be to
 * remember the <em>rule</em> and re-run it at deposit time, which sounds more flexible and is worse:
 * "nearest" evaluated an hour later, half a mining tunnel away, means the haul goes into whatever
 * container happens to be closest to wherever the bot has wandered — which may well be someone
 * else's.</p>
 */
public final class ChestAction implements Action {

	/** {@code off} is not a way of finding a chest, so it sits alongside the sources rather than in them. */
	private static final String OFF = "off";

	@Override
	public String name() {
		return "chest";
	}

	@Override
	public String description() {
		return "Choose the container to bank the haul in. Once set, the bot breaks off a mining run or "
				+ "a hunt when its inventory fills, walks back, empties out everything it has gathered "
				+ "and resumes where it left off. Pass 'off' to stop banking and keep everything.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.choice("source",
				"How to pick the container. 'looking' takes the one the player's crosshair is on, which "
						+ "is the one to use when several stand side by side. 'nearest' takes the closest "
						+ "one to the player. 'off' forgets the chest and stops banking. "
						+ "Defaults to the chestSource setting.",
				false,
				Stream.concat(
						Arrays.stream(ChestSource.values()).map(SettingChoice::key),
						Stream.of(OFF)).toList()));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		if (OFF.equalsIgnoreCase(arguments.getString("source", ""))) {
			context.controller().setDepositChest(null);
			return ActionResult.ok("Banking off — the bot will keep everything it gathers.");
		}

		ChestSource source = arguments.getChoice(
				"source", ChestSource.values(), BotSettings.CHEST_SOURCE.get());

		BlockPos found = source.resolve(context.minecraft(), context.player());
		if (found == null) {
			return ActionResult.failed("Couldn't find a container by " + source.key() + ". " + source.hint());
		}

		context.controller().setDepositChest(found);
		return ActionResult.ok("Banking the haul at " + found.getX() + ", " + found.getY() + ", "
				+ found.getZ() + " once the inventory is "
				+ Math.round(BotSettings.DEPOSIT_FULLNESS.get() * 100) + "% full.");
	}
}

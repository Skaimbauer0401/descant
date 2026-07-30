package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Describes the bot's surroundings, or one named block.
 *
 * <p>{@code status} says what the bot is <em>doing</em> and how it is faring; this says what is
 * <em>around</em> it. The two overlap on where and when — deliberately, since both are read on their
 * own and a report that leaves out the dimension is a report a model has to follow up on — but only
 * this one describes the ground underfoot, the block ahead, and every creature rather than only the
 * dangerous ones. The shared facts come from {@link Surroundings} so the two cannot come to disagree
 * about them.</p>
 *
 * <p>Everything here is cheap and read-only, which is deliberate: a model that has to spend an action
 * to look around will not bother, and will guess instead.</p>
 */
public final class LookAction implements Action {

	@Override
	public String name() {
		return "look";
	}

	@Override
	public String description() {
		return "Describe the bot's surroundings in full: exact coordinates, which way it faces, the "
				+ "dimension and biome, the time of day and weather, the light level, what it is "
				+ "standing on and what is directly ahead, and every creature or player nearby — not "
				+ "just the hostile ones. Pass 'block' with coordinates to describe one exact spot "
				+ "instead, which is how to check somewhere before building there. Costs nothing and "
				+ "changes nothing.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.optional("block", ParameterType.STRING,
				"Optionally, coordinates 'x y z' to report the block at instead of the bot's own "
						+ "surroundings — useful for checking a spot before building there."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		LocalPlayer player = context.player();
		ClientLevel level = context.minecraft().level;

		if (arguments.has("block")) {
			return describeBlock(level, arguments.getString("block"));
		}

		BlockPos feet = BlockPos.containing(player.position());
		StringBuilder text = new StringBuilder();

		text.append("At ").append((int) player.getX()).append(", ").append((int) player.getY())
				.append(", ").append((int) player.getZ())
				.append(" facing ").append(player.getDirection().getName());

		text.append(" | ").append(Surroundings.dimension(level));
		text.append(", ").append(Surroundings.biome(level, feet));
		text.append(" | ").append(Surroundings.clock(level));
		text.append(" | light ").append(level.getMaxLocalRawBrightness(feet)).append("/15");
		text.append(" | standing on ").append(Surroundings.blockName(level, feet.below()));
		text.append(", facing ").append(Surroundings.blockName(level,
				feet.relative(player.getDirection())));
		text.append(" | nearby: ").append(Surroundings.creatures(level, player));

		return ActionResult.ok(text.toString());
	}

	/** What is at one named spot, for checking somewhere before walking or building there. */
	private static ActionResult describeBlock(ClientLevel level, String raw) {
		String[] parts = raw.trim().split("[ ,]+");
		if (parts.length != 3) {
			return ActionResult.failed("'block' takes three numbers, like '100 64 -200'.");
		}
		BlockPos pos;
		try {
			pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return ActionResult.failed("'block' takes three whole numbers, like '100 64 -200'.");
		}
		if (!level.isLoaded(pos)) {
			// A real distinction: not loaded means "come closer and ask again", not "there is nothing".
			return ActionResult.failed("That spot is not loaded, so there is nothing to report. Travel "
					+ "closer and ask again.");
		}
		return ActionResult.ok(Surroundings.blockName(level, pos) + " at " + pos.getX() + ", "
				+ pos.getY() + ", " + pos.getZ() + ", with "
				+ Surroundings.blockName(level, pos.above()) + " above and "
				+ Surroundings.blockName(level, pos.below()) + " below.");
	}
}

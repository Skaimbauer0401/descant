package mcbot.client.api.actions;

import java.util.List;
import java.util.Set;

import mcbot.client.BotSettings;
import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.path.BlockSearcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Says where the nearest block of a kind is, without going anywhere.
 *
 * <p>The counterpart to {@code find}, and the difference is the point: {@code find} commits the bot
 * to a journey, while this only answers a question. That makes it safe to call while deciding — "is
 * there a crafting table around here, or do I need to place one?" is a question worth asking
 * <em>before</em> walking two hundred blocks to answer it.</p>
 *
 * <p>Blocks only. A mob's coordinates are wrong by the time they are read, so {@code find} — which
 * keeps re-aiming at it — is the only sensible way to go after one.</p>
 */
public final class LocateAction implements Action {

	@Override
	public String name() {
		return "locate";
	}

	@Override
	public String description() {
		return "Report the exact coordinates of the nearest blocks of a kind, and how far away each is, "
				+ "without moving the bot. Costs nothing. Gives several by default, nearest first, so "
				+ "you can compare them — which of three furnaces is closest, whether the ore is all in "
				+ "one direction, whether it is worth going at all. Results are spread out, so a vein "
				+ "of ore is reported once rather than filling the list with its own blocks. Use it to "
				+ "check whether something already exists nearby before deciding whether to walk to it, "
				+ "place one, or go somewhere else. To actually go there, pass one set of coordinates "
				+ "to goto, or use find.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("block", ParameterType.STRING,
						"The block id to look for, such as 'crafting_table', 'furnace' or 'diamond_ore'."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many to report at most, nearest first. Defaults to the locateCount "
								+ "setting. Ask for 1 when you only need somewhere to go, and more when "
								+ "you are working out where things are."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("block");
		Block block = ItemNames.resolveBlock(wanted);
		if (block == null) {
			return ActionResult.failed(ItemNames.unknownBlock(wanted));
		}
		int count = arguments.getInt("count", BotSettings.LOCATE_COUNT.get());
		if (count < 1) {
			return ActionResult.failed("'count' must be at least 1.");
		}

		// Ores come in stone-type variants and the caller almost never means only one of them.
		Set<Block> family = BlockFamily.of(block);
		String named = BlockFamily.describe(family);

		BlockPos from = BlockPos.containing(context.player().position());
		int radius = BotSettings.BLOCK_SEARCH_RADIUS.get();
		List<BlockPos> found = BlockSearcher.findNearest(
				context.minecraft().level, from, radius, count, BotSettings.LOCATE_SPACING.get(),
				state -> family.contains(state.getBlock()));

		if (found.isEmpty()) {
			// Worth saying why rather than just "no": the search only sees loaded chunks, so "none
			// here" and "none in the world" are different answers and only one of them means give up.
			return ActionResult.failed("No " + named + " within " + radius + " blocks of "
					+ describe(from) + ". Only loaded terrain can be searched, so travelling somewhere "
					+ "else and asking again may well find one.");
		}

		StringBuilder text = new StringBuilder();
		text.append(found.size()).append("x ").append(named);
		// A short list means the search ran out of matches, not that it stopped counting — and those
		// are different facts. "All there is" tells the caller not to bother asking for more.
		if (found.size() < count) {
			text.append(" — all there is within ").append(radius).append(" blocks");
		}
		text.append(": ");
		for (int index = 0; index < found.size(); index++) {
			BlockPos pos = found.get(index);
			text.append(index == 0 ? "" : "; ").append(describe(pos))
					.append(" (").append(Math.round(Math.sqrt(pos.distSqr(from)))).append(" away)");
		}
		return ActionResult.ok(text.append(".").toString());
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

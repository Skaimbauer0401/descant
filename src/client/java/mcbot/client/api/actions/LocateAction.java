package mcbot.client.api.actions;

import java.util.List;

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
		return "Report the exact coordinates of the nearest block of a kind, and how far away it is, "
				+ "without moving the bot. Costs nothing. Use it to check whether something already "
				+ "exists nearby — a crafting table, a furnace, a chest — before deciding whether to "
				+ "walk to it, place one, or go somewhere else. To actually go there, pass the "
				+ "coordinates to goto, or use find.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.required("block", ParameterType.STRING,
				"The block id to look for, such as 'crafting_table', 'furnace' or 'diamond_ore'."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("block");
		Block block = ItemNames.resolveBlock(wanted);
		if (block == null) {
			return ActionResult.failed(ItemNames.unknownBlock(wanted));
		}

		BlockPos from = BlockPos.containing(context.player().position());
		int radius = BotSettings.BLOCK_SEARCH_RADIUS.get();
		BlockPos found = BlockSearcher.findNearest(
				context.minecraft().level, from, radius, state -> state.is(block));

		if (found == null) {
			// Worth saying why rather than just "no": the search only sees loaded chunks, so "none
			// here" and "none in the world" are different answers and only one of them means give up.
			return ActionResult.failed("No " + wanted + " within " + radius + " blocks of "
					+ describe(from) + ". Only loaded terrain can be searched, so travelling somewhere "
					+ "else and asking again may well find one.");
		}

		return ActionResult.ok(wanted + " at " + describe(found) + ", "
				+ Math.round(Math.sqrt(found.distSqr(from))) + " blocks away.");
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

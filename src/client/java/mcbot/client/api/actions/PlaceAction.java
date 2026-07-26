package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.action.BlockPlacer;
import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.control.TravelMode;
import mcbot.client.inventory.InventoryManager;
import mcbot.client.path.WorldView;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Puts a specific block down somewhere specific — a furnace, a crafting table, a torch.
 *
 * <p>Distinct from the placing the pathfinder does on its own, which is anonymous scaffolding: any
 * spare block, wherever the route needs one. Here the block and the spot are both named, and if the
 * named block is not carried the action fails rather than substituting something else, because
 * putting down dirt when asked for a furnace is worse than not managing it.</p>
 *
 * <p>Both kinds can happen in one call, which is why {@code scaffold} appears here too: reaching a
 * distant spot is an ordinary journey and spends ordinary scaffolding on the way. The two never get
 * confused — {@code block} is what ends up at the destination, {@code scaffold} is what is spent
 * getting there.</p>
 */
public final class PlaceAction implements Action {

	/** How far below the spot in front to look for somewhere the block would actually rest. */
	private static final int GROUND_SEARCH = 3;

	@Override
	public String name() {
		return "place";
	}

	@Override
	public String description() {
		return "Put a block down. Give x, y and z for an exact spot, or leave them out to place it on "
				+ "the ground just in front of the bot, which is usually what you want for a furnace "
				+ "or a crafting table. The block has to be in the inventory already — check with "
				+ "'inventory'. THIS TRAVELS: if the spot is out of reach the bot goes there by itself, "
				+ "exactly as goto would. By default it WALKS, and only mines and bridges if there is no "
				+ "way on foot — pass travel='walk' to forbid that near anything built. The named block "
				+ "is what gets placed at the spot; 'scaffold' is only what is spent getting there.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("block", ParameterType.STRING,
						"The block id to place, such as 'furnace', 'crafting_table' or 'torch'."),
				Parameter.optional("x", ParameterType.INTEGER, "East-west coordinate of the spot."),
				Parameter.optional("y", ParameterType.INTEGER, "Height of the spot."),
				Parameter.optional("z", ParameterType.INTEGER, "North-south coordinate of the spot."),
				Travel.PARAMETER,
				Scaffold.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("block");
		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}
		if (!(item instanceof BlockItem)) {
			return ActionResult.failed(wanted + " is not a block, so it cannot be placed.");
		}
		if (!InventoryManager.has(context.player(), stack -> stack.is(item))) {
			return ActionResult.failed("No " + wanted + " in the inventory to place.");
		}

		TravelMode mode = Travel.mode(arguments);
		ActionResult rejected = Scaffold.choose(arguments);
		if (rejected != null) {
			return rejected;
		}

		LocalPlayer player = context.player();
		Level level = player.level();

		// All three or none: two coordinates do not describe a spot, and guessing the third would put
		// the block somewhere nobody asked for.
		boolean anyGiven = arguments.has("x") || arguments.has("y") || arguments.has("z");
		BlockPos target;
		if (anyGiven) {
			if (!(arguments.has("x") && arguments.has("y") && arguments.has("z"))) {
				return ActionResult.failed("Give x, y and z together, or none of them to place it in "
						+ "front of the bot.");
			}
			target = new BlockPos(arguments.getInt("x"), arguments.getInt("y"), arguments.getInt("z"));
			if (!WorldView.isFillable(level, target)) {
				return ActionResult.failed("Something is already at " + describe(target) + ".");
			}
		} else {
			target = spotInFront(player, level);
			if (target == null) {
				return ActionResult.failed("Nowhere to put it just in front — no clear spot with "
						+ "anything solid beside it. Move somewhere more open, or give x, y and z.");
			}
		}

		if (!BlockPlacer.hasAnchor(level, target)) {
			// A block can only be placed by clicking a face of a neighbour, so a cell floating in open
			// air is not placeable at all. Better said now than after walking there.
			return ActionResult.failed("Nothing solid next to " + describe(target)
					+ " to place against. Pick a spot touching the ground or a wall.");
		}

		context.controller().buildAt(context.minecraft(), player, target, item, mode);
		// The scaffolding note only earns its space when there is actually a journey. Saying what will
		// be spent bridging to a block already at arm's length is noise on every torch the bot places.
		boolean travels = player.getEyePosition().distanceTo(Vec3.atCenterOf(target))
				> BotSettings.REACH.get();
		return ActionResult.okQuiet("Placing " + wanted + " at " + describe(target) + "."
				+ (travels ? Travel.note(mode) : ""));
	}

	/**
	 * Somewhere sensible to put a block the player did not give a position for.
	 *
	 * <p>Tries the cell the bot is facing at foot level, then down for the ground when standing at the
	 * top of a drop, then up when facing a wall. That order matches what "just put it down here"
	 * means: on the floor in front, not floating at head height.</p>
	 */
	private static BlockPos spotInFront(LocalPlayer player, Level level) {
		Direction facing = player.getDirection();
		BlockPos ahead = BlockPos.containing(player.position()).relative(facing);

		if (usable(level, ahead)) {
			return ahead;
		}
		for (int drop = 1; drop <= GROUND_SEARCH; drop++) {
			BlockPos below = ahead.below(drop);
			if (usable(level, below)) {
				return below;
			}
		}
		return usable(level, ahead.above()) ? ahead.above() : null;
	}

	private static boolean usable(Level level, BlockPos pos) {
		return level.isLoaded(pos) && WorldView.isFillable(level, pos) && BlockPlacer.hasAnchor(level, pos);
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

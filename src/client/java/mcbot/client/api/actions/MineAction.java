package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks the block at one exact spot.
 *
 * <p>The counterpart to {@code place}, and distinct from {@code find} in the way that matters:
 * {@code find} hunts a <em>kind</em> of block wherever it happens to be, whereas this breaks
 * <em>that</em> block and nothing else. "Clear the stone at 100, 64, -200" and "go and mine some
 * stone" are different instructions, and only one of them was expressible before.</p>
 *
 * <p>The bot walks over if the spot is out of reach, breaks it, and sweeps up what it dropped.</p>
 */
public final class MineAction implements Action {

	@Override
	public String name() {
		return "mine";
	}

	@Override
	public String description() {
		return "Break the block at exact coordinates and pick up what it drops, walking there first if "
				+ "needed. Use this to clear one known spot — to dig out a doorway, or to remove "
				+ "something in the way. To gather a resource wherever it happens to be, use find "
				+ "instead; this only ever breaks the one block you name.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("x", ParameterType.INTEGER, "East-west coordinate of the block."),
				Parameter.required("y", ParameterType.INTEGER, "Height of the block."),
				Parameter.required("z", ParameterType.INTEGER, "North-south coordinate of the block."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		BlockPos target = new BlockPos(
				arguments.getInt("x"), arguments.getInt("y"), arguments.getInt("z"));
		ClientLevel level = context.minecraft().level;

		if (!level.isLoaded(target)) {
			return ActionResult.failed("That spot is not loaded, so there is nothing to break yet. "
					+ "Travel closer and try again.");
		}
		BlockState state = level.getBlockState(target);
		if (state.isAir()) {
			return ActionResult.failed("Nothing at " + describe(target) + " — it is already empty.");
		}

		// Bedrock and the like report a negative hardness, meaning no tool will ever get through. Worth
		// catching here: the breaker would otherwise swing at it until its timeout ran out.
		if (state.getDestroySpeed(level, target) < 0.0f) {
			return ActionResult.failed(name(state) + " at " + describe(target) + " cannot be broken.");
		}

		if (!context.controller().mineAt(context.minecraft(), context.player(), target, name(state))) {
			return ActionResult.failed("Couldn't start on " + describe(target) + ".");
		}
		return ActionResult.okQuiet("Breaking the " + name(state) + " at " + describe(target) + ".");
	}

	private static String name(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

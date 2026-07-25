package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Right-clicks a block: a lever, a button, a door, a bed, a station.
 *
 * <p>The generic interaction, and it is worth being plain about its limit. It <em>opens</em> a
 * furnace, a smithing table or an anvil, but it cannot then operate them — each is a different menu
 * with different slots and different rules, and driving one is a feature of its own. What this does
 * fully is everything that <em>is</em> a single click: switches, doors, trapdoors, gates, beds,
 * note blocks, repeaters.</p>
 */
public final class UseAction implements Action {

	@Override
	public String name() {
		return "use";
	}

	@Override
	public String description() {
		return "Right-click a block — flip a lever, press a button, open a door or gate, sleep in a "
				+ "bed. The bot walks over if it is out of reach. Give x, y and z, or leave them out "
				+ "to use whatever the bot is looking at. This opens a furnace or smithing table but "
				+ "cannot yet put items into one, so do not expect to smelt with it.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.optional("x", ParameterType.INTEGER, "East-west coordinate of the block."),
				Parameter.optional("y", ParameterType.INTEGER, "Height of the block."),
				Parameter.optional("z", ParameterType.INTEGER, "North-south coordinate of the block."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		LocalPlayer player = context.player();
		boolean anyGiven = arguments.has("x") || arguments.has("y") || arguments.has("z");

		BlockPos target;
		if (anyGiven) {
			if (!(arguments.has("x") && arguments.has("y") && arguments.has("z"))) {
				return ActionResult.failed("Give x, y and z together, or none of them to use whatever "
						+ "the bot is looking at.");
			}
			target = new BlockPos(arguments.getInt("x"), arguments.getInt("y"), arguments.getInt("z"));
		} else {
			// Same raycast the chest picker uses, so "the one I am looking at" means the same thing
			// throughout, and reaches further than the vanilla interaction limit.
			HitResult hit = player.pick(BotSettings.CHEST_LOOK_RANGE.get(), 0.0f, false);
			if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
				return ActionResult.failed("Not looking at any block. Point at one, or give x, y and z.");
			}
			target = blockHit.getBlockPos();
		}

		if (!context.minecraft().level.isLoaded(target)) {
			return ActionResult.failed("That spot is not loaded. Travel closer and try again.");
		}
		if (context.minecraft().level.getBlockState(target).isAir()) {
			return ActionResult.failed("There is nothing at " + target.getX() + ", " + target.getY()
					+ ", " + target.getZ() + " to use.");
		}

		String what = BuiltInRegistries.BLOCK
				.getKey(context.minecraft().level.getBlockState(target).getBlock()).getPath();
		context.controller().useBlock(context.minecraft(), player, target);
		return ActionResult.okQuiet("Using the " + what + " at " + target.getX() + ", " + target.getY()
				+ ", " + target.getZ() + ".");
	}
}

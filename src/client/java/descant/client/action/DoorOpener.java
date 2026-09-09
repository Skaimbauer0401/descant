package descant.client.action;

import descant.client.BotSettings;
import descant.client.control.Steering;
import descant.client.path.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Opens a door or fence gate standing in the way.
 *
 * <p>The planner treats an openable door as passable whether or not it is currently open — that is
 * Baritone's rule, and it is what stops the bot tunnelling through a wall beside a perfectly good
 * doorway. This is the other half of that bargain: when the route actually reaches a shut door, it
 * has to be opened rather than walked into.</p>
 *
 * <p>Deliberately not an {@link ActionState} state machine like the breaker and the placer. Opening
 * a door is one click with an immediate result, so making the bot stop and enter a dedicated state
 * for it would cost more ticks than the door does. Instead this is fired in passing during ordinary
 * path following, and the walk carries straight on through.</p>
 */
public final class DoorOpener {

	/** Ticks to wait after a click before trying again, so one door is not spam-clicked shut again. */
	private static final int COOLDOWN_TICKS = 6;

	private int cooldown;

	public void tick() {
		if (cooldown > 0) {
			cooldown--;
		}
	}

	public void reset() {
		cooldown = 0;
	}

	/**
	 * Opens the door at {@code pos} if it is shut and within reach.
	 *
	 * <p>A fence gate and the two halves of a door all toggle from a single click anywhere on them,
	 * so this aims at the block centre and does not care which face it hits.</p>
	 *
	 * @return whether a click was issued this tick
	 */
	public boolean open(Minecraft minecraft, LocalPlayer player, BlockPos pos) {
		if (cooldown > 0 || minecraft.gameMode == null || minecraft.level == null) {
			return false;
		}
		WorldView world = new WorldView(minecraft.level);
		if (!world.isKnown(pos) || !world.isShutDoor(pos)) {
			return false;
		}

		Vec3 centre = Vec3.atCenterOf(pos);
		Vec3 eye = player.getEyePosition();
		if (eye.distanceTo(centre) > BotSettings.REACH.get()) {
			return false; // still too far; keep walking and try again as we close
		}

		// Look at it. Aiming snaps rather than easing: the click happens this tick, so there is no
		// time to turn into position, and unlike the walk heading a momentary look does not steer us.
		player.setYRot(Steering.approach(player.getYRot(), Steering.yawTowards(player.position(), centre)));
		player.setXRot(Steering.approach(player.getXRot(), Steering.pitchTowards(eye, centre)));

		Direction face = Direction.getApproximateNearest(
				eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
		minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(centre, face, pos, false));
		player.swing(InteractionHand.MAIN_HAND);
		cooldown = COOLDOWN_TICKS;
		return true;
	}
}

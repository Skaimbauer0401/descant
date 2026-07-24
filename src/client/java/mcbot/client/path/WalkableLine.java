package mcbot.client.path;

import mcbot.client.BotSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Tests whether the player could walk from one point to another in a dead straight line.
 *
 * <p>This is the gate on steering directly at something instead of routing to it. Chasing a fleeing
 * animal or stepping onto a dropped item is far better served by walking straight at it than by
 * replanning a path every time it moves — but "walk at it and hop over whatever you bump into" is
 * only a sound strategy when there is genuinely nothing in the way, and this is what decides that.</p>
 *
 * <p>This class used to also do string-pulling, collapsing A*'s grid staircase by aiming at the
 * furthest node reachable in a straight line. That is gone: the search already emits diagonal moves,
 * so its output is smooth to begin with, and smoothing on top of it aimed the bot several blocks
 * ahead through terrain the planner had never validated as a straight run <em>at speed</em>. The bot
 * cut corners with momentum nobody had accounted for, which is what made ledges and corners hard.
 * Following one movement at a time, as Baritone does, is both simpler and steadier.</p>
 */
public final class WalkableLine {

	private WalkableLine() {
	}

	/**
	 * Tests whether the player can walk directly from {@code from} to {@code to} without hitting
	 * anything or walking off an edge.
	 *
	 * <p>Samples three parallel lines — the centre and one on each flank at the player's half
	 * width — because a centre-only test would happily cut a corner that the player's shoulders
	 * would collide with.</p>
	 */
	public static boolean canWalkStraight(WorldView world, Vec3 from, BlockPos to) {
		Vec3 target = Vec3.atBottomCenterOf(to);
		double dx = target.x - from.x;
		double dz = target.z - from.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < 1.0e-3) {
			return true;
		}

		// Unit normal to the direction of travel, scaled to the player's half width.
		double offsetX = -dz / distance * BotSettings.PLAYER_HALF_WIDTH;
		double offsetZ = dx / distance * BotSettings.PLAYER_HALF_WIDTH;

		int samples = (int) Math.ceil(distance / BotSettings.LINE_SAMPLE_SPACING);
		int y = to.getY();

		for (int sample = 0; sample <= samples; sample++) {
			double progress = (double) sample / samples;
			double x = from.x + dx * progress;
			double z = from.z + dz * progress;

			for (int side = -1; side <= 1; side++) {
				BlockPos feet = BlockPos.containing(x + offsetX * side, y, z + offsetZ * side);
				if (!world.isKnown(feet) || !world.fitsAt(feet) || !world.isStandable(feet.below())) {
					return false;
				}
			}
		}
		return true;
	}
}

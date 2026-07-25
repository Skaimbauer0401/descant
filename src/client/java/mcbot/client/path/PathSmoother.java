package mcbot.client.path;

import mcbot.client.BotSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * String-pulling: collapses the grid staircase A* produces into straight lines.
 *
 * <p>A* works on a block grid, so even a dead-straight walk comes back as a sequence of one-block
 * hops. Aiming at each in turn makes the player stop at every block centre, snap to a new heading,
 * and clip the corners in between — which looks jerky and, because Minecraft cancels sprinting on a
 * non-minor horizontal collision, also kills sprint repeatedly. Aiming instead at the
 * <em>furthest</em> node still reachable in a straight walkable line traces one smooth line over
 * identical terrain.</p>
 *
 * <p><b>This was removed once and put back.</b> The reasoning for removing it was that Baritone does
 * no smoothing — which is true, but Baritone can afford not to: its rotations are applied at full
 * rate for physics, and its movements are driven by per-movement state machines. Ours eases the walk
 * heading at a fixed degrees-per-tick, and easing towards a target only one block away means the bot
 * is still turning when it arrives, so it turns again, and again. Smoothing and eased steering are a
 * matched pair; taking one away without replacing the other made ordinary walking stop-start. The
 * general lesson: a foreign design's parts interlock, and copying one without its counterpart is not
 * fidelity, it is breakage.</p>
 */
public final class PathSmoother {

	private PathSmoother() {
	}

	/**
	 * Index of the furthest node reachable from {@code from} in a straight line.
	 *
	 * <p>Stops early at any node needing a block broken or placed, at any change of height, and at
	 * any jump: those have to be approached exactly as planned, so only flat ordinary runs get
	 * shortcut.</p>
	 *
	 * @return an index in {@code [startIndex, …]}, never less than {@code startIndex}
	 */
	public static int furthestReachable(WorldView world, Vec3 from, Path path, int startIndex) {
		int best = startIndex;
		int limit = Math.min(path.size() - 1, startIndex + BotSettings.MAX_LOOKAHEAD_STEPS.get());
		int feetY = Mth.floor(from.y);

		for (int index = startIndex; index <= limit; index++) {
			Path.Step step = path.step(index);
			// A jump must be launched from its own launch block, so a shortcut past it would skip the
			// run-up entirely — never smooth across one.
			if (step.needsWork() || step.parkour() || step.pos().getY() != feetY) {
				break;
			}
			if (!canWalkStraight(world, from, step.pos())) {
				break;
			}
			best = index;
		}
		return best;
	}

	/**
	 * Tests whether the player can walk directly from {@code from} to {@code to} without hitting
	 * anything or walking off an edge.
	 *
	 * <p>Samples three parallel lines — the centre and one on each flank at the player's half width —
	 * because a centre-only test would happily cut a corner that the player's shoulders would hit.</p>
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

		int samples = (int) Math.ceil(distance / BotSettings.LINE_SAMPLE_SPACING.get());
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

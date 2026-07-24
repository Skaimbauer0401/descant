package mcbot.client.control;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * View-direction maths.
 *
 * <p>Two turn modes, split by purpose:</p>
 * <ul>
 *   <li><b>Aiming snaps</b> ({@link #approach(float, float)}). When the bot is standing still to mine,
 *   place, fight or fire a jump, the view jumps straight onto the target in one tick, so the action
 *   fires immediately instead of burning ticks rotating into position.</li>
 *   <li><b>Steering eases</b> ({@link #approach(float, float, float)}). The <em>walk</em> heading is
 *   derived from yaw, so snapping it makes the bot overshoot corners and edges — the feet point the
 *   new way instantly while momentum still carries it the old way, walking it off ledges. Navigation
 *   turns are therefore rate-limited into the smooth arcs a person walking would trace.</li>
 * </ul>
 */
public final class Steering {

	private Steering() {
	}

	/**
	 * Yaw, in degrees, that points from {@code from} towards {@code to}.
	 *
	 * <p>Minecraft's yaw is zero facing +Z and increases clockwise, hence the {@code -90} offset
	 * from the usual {@code atan2} convention.</p>
	 */
	public static float yawTowards(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
	}

	/** Pitch, in degrees, that points from {@code from} towards {@code to}. Negative looks up. */
	public static float pitchTowards(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return (float) (-(Mth.atan2(dy, horizontal) * (180.0 / Math.PI)));
	}

	/**
	 * Snaps {@code current} straight onto {@code target} in one step, taking the shorter way around
	 * the circle (so the returned value never accumulates full turns away from {@code current}). For
	 * aiming — mining, placing, fighting, the jump launch — where the action should fire at once.
	 */
	public static float approach(float current, float target) {
		return current + Mth.wrapDegrees(target - current);
	}

	/**
	 * Moves {@code current} towards {@code target} by at most {@code maxStep} degrees. For steering
	 * the walk heading, where snapping would overshoot corners — see the class note.
	 */
	public static float approach(float current, float target, float maxStep) {
		float delta = Mth.wrapDegrees(target - current);
		return current + Mth.clamp(delta, -maxStep, maxStep);
	}

	/** Absolute angular difference in degrees, always in {@code [0, 180]}. */
	public static float angleDifference(float current, float target) {
		return Math.abs(Mth.wrapDegrees(target - current));
	}
}

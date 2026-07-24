package mcbot.client.control;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Mutable accumulator for the key state the bot wants this tick.
 *
 * <p>Vanilla's {@link Input} is an immutable record, which is awkward to build up across several
 * decisions ("walk forward", "also jump", "no sprinting while mining"). This class collects those
 * decisions and converts once, at the end, in {@link #toVanilla()}.</p>
 *
 * <p>Because the result is fed into the same field the keyboard writes to, the bot's movement is
 * transmitted through the ordinary player-input packet and is simulated by the ordinary physics
 * code — there is no position forcing anywhere in this mod.</p>
 */
public final class BotInput {

	private boolean forward;
	private boolean backward;
	private boolean left;
	private boolean right;
	private boolean jump;
	private boolean sneak;
	private boolean sprint;

	/** Resets every key to released. Called at the start of each decision tick. */
	public void clear() {
		forward = false;
		backward = false;
		left = false;
		right = false;
		jump = false;
		sneak = false;
		sprint = false;
	}

	public BotInput forward(boolean pressed) {
		this.forward = pressed;
		return this;
	}

	public BotInput backward(boolean pressed) {
		this.backward = pressed;
		return this;
	}

	public BotInput left(boolean pressed) {
		this.left = pressed;
		return this;
	}

	public BotInput right(boolean pressed) {
		this.right = pressed;
		return this;
	}

	public BotInput jump(boolean pressed) {
		this.jump = pressed;
		return this;
	}

	public BotInput sneak(boolean pressed) {
		this.sneak = pressed;
		return this;
	}

	public BotInput sprint(boolean pressed) {
		this.sprint = pressed;
		return this;
	}

	public boolean isSprinting() {
		return sprint;
	}

	/** Converts to the record vanilla expects. Field order matches {@link Input}'s constructor. */
	public Input toVanilla() {
		return new Input(forward, backward, left, right, jump, sneak, sprint);
	}

	/**
	 * The movement vector these keys produce, as {@code (strafe, forward)}.
	 *
	 * <p>Setting the key record alone is not enough to make the player walk. {@code KeyboardInput}
	 * caches a derived movement vector in {@code ClientInput.moveVector}, and it is <em>that</em>
	 * field — not the key record — which the physics code reads. Jumping is the exception: it is
	 * read straight from the key record, which is why a bot that only sets the keys will hop on the
	 * spot without ever moving forward.</p>
	 *
	 * <p>This deliberately mirrors {@code KeyboardInput.calculateImpulse} exactly, including the
	 * final normalisation, so the bot's movement is indistinguishable from held keys.</p>
	 */
	public Vec2 toMoveVector() {
		float forwardImpulse = impulse(forward, backward);
		float strafeImpulse = impulse(left, right);
		return new Vec2(strafeImpulse, forwardImpulse).normalized();
	}

	/** Opposing keys cancel out; otherwise full deflection in the pressed direction. */
	private static float impulse(boolean positive, boolean negative) {
		if (positive == negative) {
			return 0.0f;
		}
		return positive ? 1.0f : -1.0f;
	}
}

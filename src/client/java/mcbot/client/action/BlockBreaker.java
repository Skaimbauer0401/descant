package mcbot.client.action;

import mcbot.client.BotSettings;
import mcbot.client.control.Steering;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mines a single block, driving vanilla's destroy-progress state machine.
 *
 * <p>Breaking is not instantaneous: {@code startDestroyBlock} opens the interaction and
 * {@code continueDestroyBlock} must then be called every tick until the block actually disappears.
 * Skipping ticks resets the progress bar, so this class is deliberately driven once per client
 * tick and never batched.</p>
 */
public final class BlockBreaker {

	/** How closely the view must point at the block before mining starts, in degrees. */
	private static final float AIM_TOLERANCE = 12.0f;

	/** Give up after this many ticks, in case the server silently rejects the interaction. */
	private static final int TIMEOUT_TICKS = 400;

	private BlockPos target;
	private boolean started;
	private int ticks;

	public void begin(BlockPos target) {
		this.target = target.immutable();
		this.started = false;
		this.ticks = 0;
	}

	public BlockPos target() {
		return target;
	}

	public boolean hasTarget() {
		return target != null;
	}

	/** Aborts mining and tells the server to forget the in-progress break. */
	public void cancel(Minecraft minecraft) {
		if (started && minecraft.gameMode != null) {
			minecraft.gameMode.stopDestroyBlock();
		}
		target = null;
		started = false;
		ticks = 0;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player) {
		if (target == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}

		BlockState state = minecraft.level.getBlockState(target);
		if (state.isAir()) {
			cancel(minecraft);
			return ActionState.DONE;
		}
		if (++ticks > TIMEOUT_TICKS) {
			cancel(minecraft);
			return ActionState.FAILED;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(target);
		if (eye.distanceTo(center) > BotSettings.REACH.get()) {
			return ActionState.OUT_OF_RANGE;
		}

		// Turn towards the block. Mining only begins once actually aimed, because the server
		// validates that the player is looking at the block being broken.
		float desiredPitch = Steering.pitchTowards(eye, center);
		player.setXRot(Steering.approach(player.getXRot(), desiredPitch));
		boolean aimed = Steering.angleDifference(player.getXRot(), desiredPitch) <= AIM_TOLERANCE;

		double dx = center.x - eye.x;
		double dz = center.z - eye.z;
		if (dx * dx + dz * dz > BotSettings.YAW_DEADZONE_SQR.get()) {
			float desiredYaw = Steering.yawTowards(eye, center);
			player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
			aimed &= Steering.angleDifference(player.getYRot(), desiredYaw) <= AIM_TOLERANCE;
		}
		// Otherwise the block is directly overhead or underfoot: every heading points at it
		// equally, so demanding a particular yaw would stall forever on an arbitrary target.

		if (!aimed) {
			return ActionState.WORKING;
		}

		InventoryManager.equipBestTool(minecraft, player, state);

		Direction face = faceTowards(target, eye);
		if (!started) {
			minecraft.gameMode.startDestroyBlock(target, face);
			started = true;
		} else {
			minecraft.gameMode.continueDestroyBlock(target, face);
		}
		return ActionState.WORKING;
	}

	/** Picks the block face most directly exposed to the player's eye. */
	private static Direction faceTowards(BlockPos block, Vec3 eye) {
		Vec3 toEye = eye.subtract(Vec3.atCenterOf(block));
		Direction best = Direction.UP;
		double bestDot = Double.NEGATIVE_INFINITY;

		for (Direction direction : Direction.values()) {
			double dot = toEye.x * direction.getStepX()
					+ toEye.y * direction.getStepY()
					+ toEye.z * direction.getStepZ();
			if (dot > bestDot) {
				bestDot = dot;
				best = direction;
			}
		}
		return best;
	}
}

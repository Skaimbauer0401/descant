package mcbot.client.action;

import mcbot.client.BotSettings;
import mcbot.client.control.BotInput;
import mcbot.client.control.Steering;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The MLG water bucket: survives an otherwise fatal fall by placing water on the ground below and
 * landing in it.
 *
 * <p>Water cancels fall damage entirely, so a bot that carries a bucket can take drops of any
 * height. The whole trick is timing. Place too early and the water despawns or the player drifts
 * out of it; too late and the bucket is used after impact. This aims straight down throughout the
 * fall — so the view is already correct — and triggers when the ground is within
 * {@link BotSettings#CLUTCH_TRIGGER_DISTANCE} blocks, which at terminal-ish fall speeds leaves a
 * couple of ticks of margin.</p>
 *
 * <p>Afterwards the water is scooped back up, both to keep the bucket and to avoid leaving a trail
 * of water sources across the world.</p>
 */
public final class WaterBucketClutch {

	private static final float AIM_TOLERANCE = 15.0f;

	/** How far down to look for the ground we are about to hit. */
	private static final double GROUND_SCAN = 40.0;

	/** Ticks to wait after placing before scooping the water back up. */
	private static final int RECOVER_DELAY_TICKS = 6;

	/** Abandon recovery after this long rather than stand in a puddle forever. */
	private static final int RECOVER_TIMEOUT_TICKS = 40;

	private boolean placed;
	private int sincePlaced;

	/**
	 * Whether a clutch is needed right now: falling fast enough to be hurt, with a bucket to hand.
	 *
	 * <p>Deliberately checks the <em>remaining</em> drop rather than distance fallen so far, so it
	 * also covers being knocked off a ledge rather than only planned descents.</p>
	 */
	public static boolean isNeeded(Minecraft minecraft, LocalPlayer player) {
		if (player.onGround() || player.isInWater() || minecraft.level == null) {
			return false;
		}
		if (player.getDeltaMovement().y > -BotSettings.CLUTCH_MIN_FALL_SPEED) {
			return false; // rising, or only just started falling
		}
		if (player.fallDistance < BotSettings.CLUTCH_MIN_FALL_DISTANCE) {
			// Not yet a real fall. Jumps, pillar hops and single steps down all pass the speed test
			// for a tick or two, and firing on those was hijacking the route constantly.
			return false;
		}
		if (!InventoryManager.hasWaterBucket(player)) {
			return false;
		}
		double drop = distanceToGround(minecraft, player);
		if (drop < BotSettings.CLUTCH_MIN_REMAINING_DROP) {
			return false; // already nearly down; there is no time to place anything
		}
		// Require the fall to be comfortably damaging, not merely over the line. Landing at exactly
		// the safe limit is survivable and far preferable to hijacking the route for a clutch.
		return player.fallDistance + drop
				> BotSettings.SAFE_FALL_DISTANCE + BotSettings.CLUTCH_DAMAGE_MARGIN;
	}

	public void begin() {
		placed = false;
		sincePlaced = 0;
	}

	public void cancel() {
		placed = false;
		sincePlaced = 0;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player, BotInput input) {
		if (minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}

		// No steering during a clutch: horizontal drift is what makes people miss their own water.
		input.forward(false).backward(false).left(false).right(false).sprint(false).jump(false);

		if (placed) {
			return recover(minecraft, player);
		}

		if (!InventoryManager.equipWaterBucket(minecraft, player)) {
			return ActionState.NO_MATERIAL;
		}

		// Look straight down for the whole descent, so no turning is needed at the critical moment.
		player.setXRot(Steering.approach(player.getXRot(), 90.0f));
		if (Steering.angleDifference(player.getXRot(), 90.0f) > AIM_TOLERANCE) {
			return ActionState.WORKING;
		}

		double drop = distanceToGround(minecraft, player);
		if (drop < 0) {
			return ActionState.WORKING; // nothing below yet; keep falling and keep looking
		}
		if (drop > BotSettings.CLUTCH_TRIGGER_DISTANCE) {
			return ActionState.WORKING; // too early — the water would not be there when we arrive
		}

		// Place onto the top face of the block we are about to hit.
		BlockPos ground = BlockPos.containing(player.getX(), player.getY() - drop - 0.5, player.getZ());
		Vec3 hit = Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0);
		minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, Direction.UP, ground, false));
		player.swing(InteractionHand.MAIN_HAND);

		placed = true;
		sincePlaced = 0;
		return ActionState.WORKING;
	}

	/** Scoops the water back up once we have safely landed in it. */
	private ActionState recover(Minecraft minecraft, LocalPlayer player) {
		sincePlaced++;

		if (sincePlaced > RECOVER_TIMEOUT_TICKS) {
			cancel();
			return ActionState.DONE; // landed safely; leaving the water behind is not worth stalling
		}
		if (sincePlaced < RECOVER_DELAY_TICKS || (!player.onGround() && !player.isInWater())) {
			return ActionState.WORKING; // still falling or still settling
		}

		if (!InventoryManager.equip(minecraft, player, stack -> stack.is(Items.BUCKET))) {
			cancel();
			return ActionState.DONE; // no empty bucket to collect with
		}

		HitResult target = player.pick(BotSettings.REACH, 0.0f, true);
		if (target instanceof BlockHitResult blockHit
				&& !minecraft.level.getFluidState(blockHit.getBlockPos()).isEmpty()) {
			minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			player.swing(InteractionHand.MAIN_HAND);
			cancel();
			return ActionState.DONE;
		}
		return ActionState.WORKING;
	}

	/**
	 * Distance straight down to the first solid surface, or {@code -1} when nothing is within
	 * {@link #GROUND_SCAN}.
	 */
	private static double distanceToGround(Minecraft minecraft, LocalPlayer player) {
		Vec3 from = player.position();
		Vec3 to = from.subtract(0.0, GROUND_SCAN, 0.0);
		BlockHitResult hit = minecraft.level.clip(new ClipContext(
				from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

		if (hit.getType() == HitResult.Type.MISS) {
			return -1.0;
		}
		return from.y - hit.getLocation().y;
	}
}

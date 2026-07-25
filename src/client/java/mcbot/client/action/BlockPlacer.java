package mcbot.client.action;

import mcbot.client.BotSettings;
import mcbot.client.control.BotInput;
import mcbot.client.control.Steering;
import mcbot.client.inventory.InventoryManager;
import mcbot.client.path.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places a single block, for bridging across gaps and pillaring upwards.
 *
 * <p>Minecraft has no "place block at coordinate" operation — blocks are placed by right-clicking
 * the face of an <em>existing</em> block, and the new block appears in the empty space adjacent to
 * that face. So this class works backwards: find a solid neighbour of the target, aim at the face
 * of that neighbour pointing towards the target, and click it.</p>
 *
 * <p>Sneak is held throughout. Right-clicking a chest, furnace or door would otherwise open or
 * toggle it instead of placing, and sneaking suppresses that interaction — the same thing a player
 * does when building against furniture.</p>
 */
public final class BlockPlacer {

	private static final float AIM_TOLERANCE = 10.0f;

	/** Placement is near-instant; if it has not worked in this long, something is blocking it. */
	private static final int TIMEOUT_TICKS = 60;

	private BlockPos target;
	private Block excludedBlock;

	/**
	 * The exact item to place, or {@code null} to use any throwaway building block.
	 *
	 * <p>The distinction is between scaffolding and building. Bridging a gap only needs <em>a</em>
	 * block and any cobble will do; being asked for a furnace means a furnace, and quietly putting
	 * down dirt instead would be worse than failing.</p>
	 */
	private Item required;

	private int ticks;

	public void begin(BlockPos target) {
		begin(target, null);
	}

	/**
	 * @param excludedBlock block type never to build with — the haul the bot is out collecting —
	 *                      or {@code null} to allow anything
	 */
	public void begin(BlockPos target, Block excludedBlock) {
		this.target = target.immutable();
		this.excludedBlock = excludedBlock;
		this.required = null;
		this.ticks = 0;
	}

	/**
	 * Places one specific item, failing rather than substituting.
	 *
	 * @param item the block item to place — nothing else will be used
	 */
	public void beginWith(BlockPos target, Item item) {
		this.target = target.immutable();
		this.excludedBlock = null;
		this.required = item;
		this.ticks = 0;
	}

	public BlockPos target() {
		return target;
	}

	public boolean hasTarget() {
		return target != null;
	}

	public void cancel() {
		target = null;
		excludedBlock = null;
		required = null;
		ticks = 0;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player, BotInput input) {
		if (target == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}

		if (!WorldView.isFillable(minecraft.level, target)) {
			cancel();
			return ActionState.DONE; // something already fills the space
		}
		if (++ticks > TIMEOUT_TICKS) {
			cancel();
			return ActionState.FAILED;
		}

		boolean holding = required != null
				? InventoryManager.equipItem(minecraft, player, required)
				: InventoryManager.equipBuildingBlock(minecraft, player, excludedBlock);
		if (!holding) {
			cancel();
			return ActionState.NO_MATERIAL;
		}

		Direction toAnchor = findAnchorDirection(minecraft.level, target);
		if (toAnchor == null) {
			cancel();
			return ActionState.FAILED; // nothing solid to build off
		}

		BlockPos anchor = target.relative(toAnchor);
		Direction clickedFace = toAnchor.getOpposite();
		Vec3 hit = Vec3.atCenterOf(anchor).add(
				clickedFace.getStepX() * 0.5,
				clickedFace.getStepY() * 0.5,
				clickedFace.getStepZ() * 0.5);

		Vec3 eye = player.getEyePosition();
		if (eye.distanceTo(hit) > BotSettings.REACH.get()) {
			return ActionState.OUT_OF_RANGE;
		}

		// Stand still while building, whichever mode we are in.
		input.forward(false).backward(false).left(false).right(false).sprint(false);

		// Are we physically in the way? This is the actual rule Minecraft enforces — a block cannot
		// be placed into a space an entity occupies — so test it directly against the hitbox.
		//
		// Comparing block positions instead (does containing(position) equal the target?) fails on
		// edges and corners: standing on the lip of a block puts the feet position in a *neighbour*
		// while the body still overlaps the target. The bot then took the bridging path, sneaked,
		// and retried a placement into itself until the timeout.
		boolean pillaring = player.getBoundingBox().intersects(new AABB(target));

		float desiredPitch = Steering.pitchTowards(eye, hit);
		player.setXRot(Steering.approach(player.getXRot(), desiredPitch));
		boolean aimed = Steering.angleDifference(player.getXRot(), desiredPitch) <= AIM_TOLERANCE;

		if (pillaring) {
			// Yaw is meaningless when looking straight down — the target is directly below, so
			// every heading is equally correct. Forcing one would waste the airborne window.
			input.sneak(false);

			// Centre up before jumping. Arriving with leftover momentum leaves the player against
			// the side of the column; in a one-wide shaft they then clip the wall on the way up
			// and never get clear of the block they are trying to fill.
			if (player.onGround() && !player.isInWater() && !isCentred(player)) {
				Vec3 centre = Vec3.atBottomCenterOf(target);
				float centringYaw = Steering.yawTowards(player.position(), centre);
				player.setYRot(Steering.approach(player.getYRot(), centringYaw));

				// Sneaking while shuffling stops us walking off a one-block pillar entirely.
				input.sneak(true);
				input.jump(false);
				input.forward(Steering.angleDifference(player.getYRot(), centringYaw) < 45.0f);
				return ActionState.WORKING;
			}

			if (!aimed) {
				return ActionState.WORKING; // finish turning before leaving the ground
			}

			// Press jump only while on the ground, and release it in the air. This looks like a
			// detail but decides the whole pillaring speed: jumpFromGround() sets a 10-tick
			// noJumpDelay, and the only thing that clears it early is the jump key being *released*
			// (LivingEntity.aiStep zeroes it on the not-jumping branch). Holding jump down means
			// landing after ~6 ticks with ~4 still on the clock and standing there waiting.
			// Releasing mid-air clears it, so the next jump fires the instant we touch down.
			//
			// In water there is no ground and no jump cooldown — buoyancy takes a continuously
			// held key. Gating on onGround() alone meant the key was never pressed while swimming,
			// so the player never rose clear of the block and every underwater placement timed out.
			input.jump(player.onGround() || player.isInWater());

			// Rise until the hitbox genuinely clears the space, rather than guessing a height.
			if (player.getBoundingBox().intersects(new AABB(target))) {
				return ActionState.WORKING;
			}
		} else {
			float desiredYaw = Steering.yawTowards(eye, hit);
			player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
			aimed &= Steering.angleDifference(player.getYRot(), desiredYaw) <= AIM_TOLERANCE;

			// Sneaking stops us walking off the edge we are bridging out over, and stops the click
			// opening a chest or door instead of placing.
			input.sneak(true);

			if (!aimed) {
				return ActionState.WORKING;
			}
		}

		minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, clickedFace, anchor, false));
		player.swing(InteractionHand.MAIN_HAND);

		// Success is confirmed by the block appearing, checked at the top of the next tick.
		return ActionState.WORKING;
	}

	/** Whether the player is close enough to the middle of their own column to jump cleanly. */
	private boolean isCentred(LocalPlayer player) {
		Vec3 centre = Vec3.atBottomCenterOf(target);
		double dx = centre.x - player.getX();
		double dz = centre.z - player.getZ();
		return dx * dx + dz * dz < BotSettings.PILLAR_CENTRE_TOLERANCE.get() * BotSettings.PILLAR_CENTRE_TOLERANCE.get();
	}

	/**
	 * Whether anything solid borders {@code target} to place against.
	 *
	 * <p>Exposed so a caller can pick a workable spot up front rather than sending the bot walking to
	 * one it will only fail at on arrival.</p>
	 */
	public static boolean hasAnchor(Level level, BlockPos target) {
		return findAnchorDirection(level, target) != null;
	}

	/**
	 * Finds a direction from {@code target} to a solid neighbour that can be clicked.
	 *
	 * <p>Down is tried first: placing on top of the block below is the most reliable and is what
	 * bridging needs. Sideways anchors come next, and up last.</p>
	 */
	private static Direction findAnchorDirection(Level level, BlockPos target) {
		Direction[] preference = {
				Direction.DOWN, Direction.NORTH, Direction.SOUTH,
				Direction.EAST, Direction.WEST, Direction.UP
		};
		for (Direction direction : preference) {
			BlockPos neighbour = target.relative(direction);
			if (!level.isLoaded(neighbour)) {
				continue;
			}
			// Must be a full cube, so the placed block lands where the path expects it.
			if (level.getBlockState(neighbour).isCollisionShapeFullBlock(level, neighbour)) {
				return direction;
			}
		}
		return null;
	}
}

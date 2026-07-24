package mcbot.client.control;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import mcbot.client.BotSettings;
import mcbot.client.action.ActionState;
import mcbot.client.action.BlockBreaker;
import mcbot.client.action.BlockPlacer;
import mcbot.client.action.WaterBucketClutch;
import mcbot.client.inventory.InventoryManager;
import mcbot.client.inventory.ItemScanner;
import mcbot.client.path.AirFinder;
import mcbot.client.path.BlockSearcher;
import mcbot.client.path.Path;
import mcbot.client.path.PathFinder;
import mcbot.client.path.WorldView;
import mcbot.client.path.goal.Goal;
import mcbot.client.path.goal.GoalBlock;
import mcbot.client.path.goal.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The brain: plans a route, walks it, and repairs the plan when reality disagrees.
 *
 * <p>Runs as a state machine ticked once per client tick. The interesting states are:</p>
 * <ul>
 *   <li>{@code PLANNING} — feeding the time-sliced A* search until it produces a path</li>
 *   <li>{@code FOLLOWING} — steering towards the current path node</li>
 *   <li>{@code BREAKING}/{@code PLACING} — standing still while a world edit completes</li>
 * </ul>
 *
 * <p>Replanning is the mechanism that makes long journeys work. A path is only ever a best guess
 * made from currently-loaded chunks; the bot walks it, and on reaching the end (or on getting
 * stuck) plans again from wherever it actually ended up. Distant terrain therefore gets planned
 * through once it has loaded, rather than being guessed at.</p>
 */
public final class BotController {

	public enum Status {
		IDLE,
		PLANNING,
		FOLLOWING,
		BREAKING,
		PLACING,
		CLUTCHING,
		/** Breaking the block we came to mine, having arrived beside it. */
		MINING,
		/** Walking over the drops left by a broken block. */
		COLLECTING,
		SUCCEEDED,
		FAILED
	}

	/** Ticks to wait before retrying after a failed search, giving chunks a chance to load. */
	private static final int REPLAN_COOLDOWN_TICKS = 10;

	/** How close to a hunted mob the route needs to get before close-range pursuit takes over. */
	private static final int ENTITY_GOAL_RADIUS = 2;

	/** Ticks of being stuck before trying a jump, well before giving up and replanning. */
	private static final int STUCK_JUMP_TICKS = 10;

	private final BotInput input = new BotInput();
	private final BlockBreaker breaker = new BlockBreaker();
	private final BlockPlacer placer = new BlockPlacer();
	private final WaterBucketClutch clutch = new WaterBucketClutch();
	private final Consumer<Component> messageSink;

	private Status status = Status.IDLE;

	/**
	 * What the bot is trying to achieve — not necessarily a single block. Expressing this as a
	 * {@link Goal} rather than a {@code BlockPos} is what lets one unchanged search pursue an exact
	 * block, a column at any height, a depth to reach, or a radius around something.
	 */
	private Goal goal;
	private boolean allowBreak = true;
	private boolean allowPlace = true;

	/** Sprint-jumping across gaps. Movement-only (no world edits), so it is allowed even on walk. */
	private boolean allowParkour = true;

	/** What the user asked for, so a temporary shortage does not permanently disable building. */
	private boolean allowPlaceRequested = true;

	/** The real destination, parked while the bot detours to collect something it needs. */
	private Goal resumeGoal;

	/** Water-bucket clutching is opt-in: it overrides the route, so it must be asked for. */
	private boolean clutchEnabled;

	/** When mining a block type, what we are hunting; {@code null} for an ordinary journey. */
	private Block huntedBlock;

	/** When hunting a mob: the type to look for next, and the individual currently pursued. */
	private EntityType<?> huntedType;
	private Entity huntedEntity;

	private String huntedName = "";

	/** Whether a hunt mines what it finds and moves on, or merely travels to it once. */
	private boolean huntExecute = true;

	/** Block to break on arrival, and the type expected there. The goal is a spot beside it. */
	private BlockPos mineTarget;
	private Block mineTargetBlock;

	/** Ticks spent sweeping up loot, for the grace period and the give-up timeout. */
	private int collectTicks;

	/** Sweeping up loot: the goal points at a drop rather than at the journey's destination. */
	private boolean collecting;

	/** Where the loot should be lying — the broken block, or where the mob fell. */
	private Vec3 collectAnchor;

	private PathFinder search;
	private Path path;
	private int stepIndex;
	private int breakIndex;

	/**
	 * The search for the <em>next</em> segment, run while the current one is still being walked.
	 *
	 * <p>Separate from {@link #search}, which only runs in {@code PLANNING}. The two are never active
	 * at once, so the per-tick search budget is never spent twice over.</p>
	 */
	private PathFinder lookaheadSearch;

	/**
	 * Positions of the route the pending replan is replacing, whose cost the search discounts. Captured
	 * as the old plan is torn down; empty for a journey that is starting fresh.
	 */
	private Set<Long> favouredRoute = Set.of();

	/** Consecutive ticks spent drifting off the route, before the plan is given up on. */
	private int ticksOffPath;

	private int ticksSinceProgress;
	private int furthestProgress = -1;
	private double bestGoalDistance = Double.MAX_VALUE;
	private int fruitlessReplans;
	private int planFailures;
	private int cooldown;
	private int airborneWait;

	public BotController(Consumer<Component> messageSink) {
		this.messageSink = messageSink;
	}

	// ---------------------------------------------------------------- public control

	/** Starts navigating to one exact block. Replaces any journey already in progress. */
	public void navigateTo(BlockPos goal, boolean allowBreak, boolean allowPlace) {
		navigateTo(new GoalBlock(goal), allowBreak, allowPlace);
	}

	/** Starts pursuing {@code goal}. Replaces any journey already in progress. */
	public void navigateTo(Goal goal, boolean allowBreak, boolean allowPlace) {
		this.goal = goal;
		this.allowBreak = allowBreak;
		this.allowPlace = allowPlace;
		this.allowPlaceRequested = allowPlace;
		this.resumeGoal = null;
		this.planFailures = 0;
		this.cooldown = 0;
		this.fruitlessReplans = 0;
		this.bestGoalDistance = Double.MAX_VALUE;
		this.collecting = false;
		this.favouredRoute = Set.of(); // a new journey has no incumbent route to stay loyal to
		resetPlan(null);
		this.status = Status.PLANNING;
	}

	/**
	 * Hunts down and mines the nearest block of the given kind, repeating until none are left in
	 * range.
	 *
	 * <p>The target position <em>is</em> the ore itself rather than somewhere beside it. Standing
	 * where the ore is requires breaking it, so the existing tunnel-and-mine machinery does the
	 * work — no separate mining mode, and it digs its own way in when the ore is buried.</p>
	 *
	 * @return whether a block was found to head for
	 */
	public boolean huntFor(Minecraft minecraft, LocalPlayer player, Block block, String displayName,
			boolean execute) {
		BlockPos found = BlockSearcher.findNearest(
				minecraft.level,
				BlockPos.containing(player.position()),
				BotSettings.BLOCK_SEARCH_RADIUS,
				state -> state.is(block));

		if (found == null) {
			huntedBlock = null;
			message("No " + displayName + " within " + BotSettings.BLOCK_SEARCH_RADIUS
					+ " blocks of here.");
			return false;
		}

		// Always travel to a spot *beside* the block and break it from there, rather than routing
		// into its space and relying on the path to clear it on the way.
		//
		// That indirection is what makes odd-shaped blocks work. A fence has a collision box 1.5
		// blocks high, so isStandable() reports true and the pathfinder cheerfully plans to step up
		// onto it instead of mining it — a move the physics will not perform. Mushrooms have no
		// collision at all and get walked straight through, never broken. Approaching and mining
		// explicitly sidesteps every one of these shape assumptions.
		BlockPos destination = approachPosition(minecraft, player, found);
		navigateTo(destination, true, true);

		this.mineTarget = execute ? found : null;
		this.mineTargetBlock = execute ? block : null;
		this.huntedBlock = execute ? block : null;
		this.huntedType = null;
		this.huntedEntity = null;
		this.huntedName = displayName;
		this.huntExecute = execute;

		message((execute ? "Mining " : "Heading to ") + displayName + " at " + format(found) + ".");
		return true;
	}

	/**
	 * A spot to stand while working on {@code target}.
	 *
	 * <p>Falls back to the target itself when nothing beside it is standable — a buried ore has no
	 * free neighbour, and routing into it makes the pathfinder tunnel there, breaking it on the
	 * way. Both outcomes end with the block mined.</p>
	 */
	private BlockPos approachPosition(Minecraft minecraft, LocalPlayer player, BlockPos target) {
		WorldView world = new WorldView(minecraft.level);
		BlockPos from = BlockPos.containing(player.position());
		int radius = (int) Math.ceil(BotSettings.REACH);

		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		// Anywhere within arm's length will do, not just the six touching blocks. Standing back
		// means far less digging: a spot four blocks away across open ground beats tunnelling in
		// to press against the target, and often needs no digging at all.
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos candidate = target.offset(dx, dy, dz);
					if (!world.isKnown(candidate) || !world.canStandAt(candidate)) {
						continue;
					}
					// Never stand on the block we are about to mine — breaking it drops us into the
					// hole, which is the whole "digs straight down" failure. Beside or above-beside
					// is fine; directly on top is not.
					if (candidate.below().equals(target)) {
						continue;
					}
					if (!withinReach(candidate, target)) {
						continue;
					}
					double distance = candidate.distSqr(from);
					if (distance >= bestDistance) {
						continue; // no better than what we have; skip the expensive check
					}
					if (!hasLineOfSight(minecraft, player, candidate, target)) {
						continue; // cannot break what we cannot see
					}
					bestDistance = distance;
					best = candidate;
				}
			}
		}
		return best != null ? best : target;
	}

	/** View point of a player whose feet are at {@code feet}, for a stance not yet occupied. */
	private static Vec3 eyeAt(BlockPos feet) {
		return Vec3.atBottomCenterOf(feet).add(0.0, BotSettings.STANDING_EYE_HEIGHT, 0.0);
	}

	/** Whether a player standing at {@code feet} would have a clear view of {@code target}. */
	private static boolean hasLineOfSight(Minecraft minecraft, LocalPlayer player, BlockPos feet,
			BlockPos target) {
		Vec3 eye = eyeAt(feet);
		Vec3 centre = Vec3.atCenterOf(target);

		BlockHitResult hit = minecraft.level.clip(new ClipContext(
				eye, centre, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

		// Nothing in the way, or the first thing hit is the block we want.
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}

	/** Whether a player standing at {@code feet} could reach {@code target}. */
	private static boolean withinReach(BlockPos feet, BlockPos target) {
		return eyeAt(feet).distanceTo(Vec3.atCenterOf(target)) <= BotSettings.REACH;
	}

	/**
	 * Hunts down the nearest entity of the given type and walks to it.
	 *
	 * <p>Reaching a mob puts it inside the combat range, so self-defence takes over and fights it
	 * — no separate attack mode needed. Passive animals are simply walked up to.</p>
	 *
	 * @return whether one was found to head for
	 */
	public boolean huntForEntity(Minecraft minecraft, LocalPlayer player, EntityType<?> type,
			String displayName, boolean execute) {
		AABB box = player.getBoundingBox().inflate(BotSettings.ENTITY_SEARCH_RADIUS);
		Entity nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (Entity candidate : minecraft.level.getEntitiesOfClass(Entity.class, box,
				entity -> entity.isAlive() && entity.getType() == type)) {
			double distance = candidate.distanceToSqr(player);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate;
			}
		}

		if (nearest == null) {
			huntedType = null;
			huntedEntity = null;
			message("No " + displayName + " within " + (int) BotSettings.ENTITY_SEARCH_RADIUS
					+ " blocks of here.");
			return false;
		}

		// Near, not exact. A mob has moved by the time the route is walked, so planning to its precise
		// block buys a precision that is already wrong — and standing *in* an entity is not a place you
		// can be anyway. Getting within arm's length is the actual requirement, and close-range pursuit
		// takes over from there.
		navigateTo(new GoalNear(BlockPos.containing(nearest.position()), ENTITY_GOAL_RADIUS), true, true);
		huntedBlock = null;
		huntedType = type;
		// Hold on to the individual only when following it; otherwise this is a one-off trip to where
		// it happened to be standing. Following is as far as this goes — the bot does not attack, the
		// same as Baritone's follow, which only ever keeps a goal pinned to the entity.
		huntedEntity = execute ? nearest : null;
		huntedName = displayName;
		huntExecute = execute;

		message((execute ? "Following " : "Heading to ") + displayName + " at " + describeGoal() + ".");
		return true;
	}

	/**
	 * Keeps the goal on a moving quarry.
	 *
	 * <p>A mob does not wait to be walked to. Pathing once to where it stood arrives at empty
	 * ground, so the goal is re-pointed whenever the target has wandered far enough to matter.
	 * Re-planning is skipped while actually in melee — at that range the combat behaviour is
	 * driving, and replanning every tick would fight it for control.</p>
	 */
	private void trackHuntedEntity(Minecraft minecraft) {
		if (huntedEntity == null) {
			return;
		}
		if (!huntedEntity.isAlive() || huntedEntity.isRemoved()) {
			// Nothing to follow any more. The bot no longer kills anything, so this is something else
			// having got there first, or the entity simply leaving client range.
			huntedEntity = null;
			message(huntedName + " is gone.");
			resetPlan(minecraft);
			status = Status.SUCCEEDED;
			input.clear();
			return;
		}

		BlockPos where = BlockPos.containing(huntedEntity.position());
		if (goal == null
				|| where.distSqr(goal.approximatePosition()) > BotSettings.RETARGET_DISTANCE_SQR) {
			goal = new GoalNear(where, ENTITY_GOAL_RADIUS);
			replan(minecraft);
		}
	}

	/** Stops immediately and releases all keys. */
	public void stop(Minecraft minecraft) {
		resetPlan(minecraft);
		goal = null;
		huntedBlock = null;
		huntedType = null;
		huntedEntity = null;
		mineTarget = null;
		mineTargetBlock = null;
		collecting = false;
		status = Status.IDLE;
		input.clear();
	}

	public boolean isActive() {
		return status == Status.PLANNING
				|| status == Status.FOLLOWING
				|| status == Status.BREAKING
				|| status == Status.PLACING
				|| status == Status.MINING
				|| status == Status.COLLECTING
				|| status == Status.CLUTCHING;
	}

	public BotInput input() {
		return input;
	}

	public Status status() {
		return status;
	}

	/** A block standing in for the goal, for the in-world marker and the status line. */
	public BlockPos goal() {
		return goal == null ? null : goal.approximatePosition();
	}

	/** The goal in words, for chat. */
	private String describeGoal() {
		return goal == null ? "?" : goal.describe();
	}

	/** @return the new state */
	public boolean toggleClutch() {
		clutchEnabled = !clutchEnabled;
		return clutchEnabled;
	}

	public boolean isClutchEnabled() {
		return clutchEnabled;
	}

	/** Remaining nodes on the current path, for the status display. */
	public int remainingSteps() {
		return path == null ? 0 : Math.max(0, path.size() - stepIndex);
	}

	/**
	 * The route currently being walked, or {@code null} when there is none.
	 *
	 * <p>Read from the render thread. Safe because {@link Path} is immutable once built, so the
	 * worst case is a frame drawn against the previous path.</p>
	 */
	public Path currentPath() {
		return path;
	}

	/** Index of the next node to be entered, for highlighting progress. */
	public int currentStep() {
		return stepIndex;
	}

	/** The block being mined right now, or {@code null}. For the in-world display. */
	public BlockPos activeBreakTarget() {
		return breaker.target();
	}

	/** The block being placed right now, or {@code null}. For the in-world display. */
	public BlockPos activePlaceTarget() {
		return placer.target();
	}

	/** The mob currently being hunted, or {@code null}. For the in-world display. */
	public Entity huntedEntity() {
		return huntedEntity;
	}

	/** Short description of what the bot is doing, shown in the world. */
	public String activityLabel() {
		// Loot sweeping runs through the ordinary travel states, so the flag has to win over them.
		if (collecting) {
			return "collecting";
		}
		return switch (status) {
			case PLANNING -> "planning";
			case FOLLOWING -> "travelling";
			case BREAKING, MINING -> "mining";
			case PLACING -> "building";
			case COLLECTING -> "collecting";
			case CLUTCHING -> "clutching";
			case SUCCEEDED -> "done";
			case FAILED -> "failed";
			case IDLE -> "idle";
		};
	}

	public int searchedNodes() {
		return search == null ? 0 : search.expandedNodes();
	}

	/**
	 * Aborts the journey if the human touched a movement key.
	 *
	 * <p>Called with the real keyboard state before it is overwritten. Handing control straight
	 * back on any manual input avoids the worst failure mode of a movement bot — fighting the
	 * player for control of their own character.</p>
	 */
	public void onManualInput(Minecraft minecraft, Input keyboard) {
		if (!isActive()) {
			return;
		}
		if (keyboard.forward() || keyboard.backward() || keyboard.left() || keyboard.right()
				|| keyboard.jump()) {
			stop(minecraft);
			message("Bot cancelled — manual control resumed.");
		}
	}

	// ---------------------------------------------------------------- tick

	public void tick(Minecraft minecraft) {
		if (!isActive()) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			stop(minecraft);
			return;
		}

		input.clear();
		trackProgress(minecraft, player);
		trackHuntedEntity(minecraft);
		if (!isActive()) {
			return; // the hunt finished while re-targeting
		}

		// Survival overrides, most urgent first. These pre-empt the route entirely: arriving dead
		// is not arriving.
		//
		// Checked *before* arrival, deliberately. Standing on the goal is no reason to ignore a
		// creeper, and on a mob hunt the quarry is by definition right where the goal is — testing
		// arrival first would declare the hunt complete the moment we caught up with it, instead of
		// fighting.
		if (tickSurvival(minecraft, player)) {
			return;
		}

		// Building gets switched off when the bot runs dry. Nothing used to switch it back on, so
		// one shortage disabled bridging for the rest of the journey however many blocks were
		// picked up afterwards. Restore it the moment we are carrying something usable again.
		if (!allowPlace && allowPlaceRequested
				&& InventoryManager.hasBuildingBlock(player, huntedBlock)) {
			allowPlace = true;
			message("Have blocks again — building back on.");
			replan(minecraft);
			return;
		}

		// Arriving at the goal can happen mid-path (a fall may drop us straight onto it).
		//
		// Only while actually travelling, though. Standing on the goal satisfies this test on every
		// subsequent tick too, so without the status guard succeed() re-runs continuously: it would
		// cancel and restart the breaker each tick, meaning destroy progress never accumulated and
		// the block was never mined. Once we are mining or fighting, arrival is old news.
		boolean travelling = status == Status.PLANNING || status == Status.FOLLOWING;
		if (travelling && goal != null && withinGoalTolerance(player)) {
			succeed(minecraft);
			return;
		}

		switch (status) {
			case PLANNING -> tickPlanning(minecraft, player);
			case FOLLOWING -> tickFollowing(minecraft, player);
			case BREAKING -> tickBreaking(minecraft, player);
			case PLACING -> tickPlacing(minecraft, player);
			case MINING -> tickMining(minecraft, player);
			case COLLECTING -> tickCollecting(minecraft, player);
			default -> {
			}
		}
	}

	/**
	 * Handles the two things that will kill the bot mid-route if ignored: a fatal fall and drowning.
	 *
	 * <p>Both are Baritone's scope — a water-bucket landing is {@code allowWaterBucketFall}, and
	 * neither is a "behaviour" so much as a movement the route has already committed to. Fighting and
	 * eating used to live here too and no longer do: Baritone has no combat or hunger handling at all,
	 * and this is meant to be that same base.</p>
	 *
	 * @return {@code true} when survival took over this tick and the route should not be driven
	 */
	private boolean tickSurvival(Minecraft minecraft, LocalPlayer player) {
		// 1. The ground rushing up. Nothing else matters for the next few ticks.
		if (status == Status.CLUTCHING) {
			tickClutch(minecraft, player);
			return true;
		}
		if (clutchEnabled && WaterBucketClutch.isNeeded(minecraft, player)) {
			clutch.begin();
			status = Status.CLUTCHING;
			tickClutch(minecraft, player);
			return true;
		}

		// 2. Running out of air.
		if (player.isUnderWater() && player.getAirSupply() < BotSettings.AIR_CRITICAL) {
			swimForAir(minecraft, player);
			return true;
		}
		return false;
	}

	/**
	 * Heads for the nearest breathable space.
	 *
	 * <p>Straight up is only right in open water: under an overhang it presses the player into the
	 * ceiling until they drown. Searching for the actual nearest air lets the bot swim sideways out
	 * from under an obstruction, and falls back to surfacing only when nothing is found.</p>
	 */
	private void swimForAir(Minecraft minecraft, LocalPlayer player) {
		input.forward(false).backward(false).left(false).right(false).sprint(false);

		BlockPos air = AirFinder.findNearestBreathable(
				new WorldView(minecraft.level),
				BlockPos.containing(player.position()),
				BotSettings.AIR_SEARCH_NODES);

		if (air == null) {
			input.jump(true); // nothing found in range; upward is the best remaining guess
			return;
		}

		Vec3 target = Vec3.atBottomCenterOf(air);
		float desiredYaw = Steering.yawTowards(player.position(), target);
		player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
		// Swimming follows the view direction, so the pitch is what actually drives us upward.
		player.setXRot(Steering.approach(player.getXRot(),
				Steering.pitchTowards(player.getEyePosition(), target)));

		input.forward(true);
		input.jump(target.y > player.getY() + 0.2);
	}

	private void tickClutch(Minecraft minecraft, LocalPlayer player) {
		switch (clutch.tick(minecraft, player, input)) {
			case WORKING -> {
				// keep falling, keep aiming down
			}
			case DONE -> {
				// Landed. Replan: we are almost certainly not where the old path expected.
				replan(minecraft);
			}
			case NO_MATERIAL, FAILED -> {
				clutch.cancel();
				replan(minecraft);
			}
		}
	}

	private void tickPlanning(Minecraft minecraft, LocalPlayer player) {
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		// Never start a search from mid-air. The entity position is at the feet, so at the top of a
		// jump it floors to a block one higher than the ground — the search then plans from that
		// phantom block, and once we land the path's first node sits a block above us and reads as a
		// 2-block climb we can never make. Wait to touch down (holding still so we land where we
		// are) before planning; a long committed fall falls through the timeout and plans anyway.
		if (search == null && !player.onGround() && !player.isInWater()) {
			input.forward(false).backward(false).left(false).right(false).sprint(false).jump(false);
			if (++airborneWait < BotSettings.AIRBORNE_PLAN_WAIT) {
				return;
			}
		}
		airborneWait = 0;

		if (search == null) {
			search = newSearch(minecraft, player, planStart(minecraft, player), favouredRoute);
		}

		PathFinder.State result = search.advance(BotSettings.SEARCH_BUDGET_NANOS);
		switch (result) {
			case SEARCHING -> {
				// keep chewing next tick
			}
			case SUCCESS, PARTIAL -> {
				path = search.result();
				search = null;
				stepIndex = 0;
				breakIndex = 0;
				planFailures = 0;
				status = Status.FOLLOWING;
			}
			case FAILED -> {
				search = null;
				if (++planFailures < BotSettings.MAX_REPLAN_FAILURES) {
					cooldown = REPLAN_COOLDOWN_TICKS;
				} else if (collecting) {
					abandonCollecting(minecraft); // no route to the loot; it is not worth failing over
				} else {
					fail(minecraft, "No route to " + describeGoal() + ".");
				}
			}
		}
	}

	private void tickFollowing(Minecraft minecraft, LocalPlayer player) {
		if (path == null || path.isEmpty()) {
			replan(minecraft);
			return;
		}

		// Advance progress to wherever we have actually got to. Note what stepIndex means: the next
		// node still to be *entered*, never the node we are standing on. Any mining or building that
		// node requires must happen before we move into it.
		int advanced = advanceProgress(minecraft, player);
		if (advanced != stepIndex) {
			stepIndex = advanced;
			breakIndex = 0;
		}

		if (stepIndex >= path.size()) {
			// Whole path walked. A partial path means there is more journey left to plan.
			if (path.reachesGoal()) {
				succeed(minecraft);
			} else {
				replan(minecraft);
			}
			return;
		}

		// Plan the continuation while we walk this segment, so arriving at the end of a partial route
		// never means standing still to think.
		tickLookahead(minecraft, player);

		Path.Step next = path.step(stepIndex);

		// Genuinely off-route: shoved by a mob, fell, or the terrain was not what we planned for.
		// A parkour jump is exempt: the whole point is to be airborne several blocks from both ends of
		// the movement, which would trip this every time. A missed jump instead stops making progress
		// and is caught by the stuck detector, which replans.
		if (!next.parkour() && isOffPath(player, next)) {
			replan(minecraft);
			return;
		}

		if (breakIndex < next.toBreak().size()) {
			BlockPos blocking = next.toBreak().get(breakIndex);
			if (minecraft.level.getBlockState(blocking).isAir()) {
				breakIndex++; // already gone — someone else mined it, or it was a plant
			} else {
				breaker.begin(blocking);
				status = Status.BREAKING;
			}
			return;
		}

		if (next.toPlace() != null && needsFilling(minecraft, next.toPlace())) {
			// Spend the block we are out here collecting on our own scaffolding: on a hunt we
			// accumulate a lot of it, so it is the cheapest thing to build with.
			placer.begin(next.toPlace(), huntedBlock);
			status = Status.PLACING;
			return;
		}

		if (next.parkour()) {
			driveParkour(player, next);
			return;
		}

		if (isClimbing(minecraft, next)) {
			driveClimb(minecraft, player, next);
			return;
		}

		// Walk to the next node, and only the next node. There is deliberately no string-pulling here
		// any more: A* already emits diagonal moves, so the route it returns is smooth in the only
		// sense that matters, and smoothing on top of it aimed the bot at a waypoint several blocks
		// away that the search had never checked was reachable in a straight line at speed. That is
		// what made corners and ledges hard — the bot cut them with momentum the planner never
		// accounted for. Baritone executes one movement at a time for exactly this reason.
		walkTowards(minecraft, player, next);
	}

	/**
	 * Plans the next segment of a partial route while the current one is still being walked, and
	 * splices it on when it is ready.
	 *
	 * <p>A search only ever produces a route through terrain the client has actually loaded, so a long
	 * journey is necessarily a chain of segments. Planning each one only on arriving at the end of the
	 * last meant a visible stall every time — the bot stopping dead, thinking, then setting off again.
	 * Overlapping the search with the walk removes the stall without changing the route: the
	 * continuation is searched from where this segment ends, so it joins on seamlessly.</p>
	 */
	private void tickLookahead(Minecraft minecraft, LocalPlayer player) {
		if (lookaheadSearch == null) {
			if (path.reachesGoal() || goal == null) {
				return; // this route already arrives; there is no continuation to plan
			}
			if (path.remainingTicks(stepIndex) > BotSettings.PLANNING_TICK_LOOKAHEAD) {
				return; // plenty of route left; no need to think about the next segment yet
			}
			BlockPos from = path.destination();
			if (from == null) {
				return;
			}
			// No favouring here: a continuation is searched from beyond the end of the current route,
			// so there is no incumbent course through that terrain to stay loyal to.
			lookaheadSearch = newSearch(minecraft, player, from, Set.of());
		}

		switch (lookaheadSearch.advance(BotSettings.SEARCH_BUDGET_NANOS)) {
			case SEARCHING -> {
				// keep chewing next tick, while we walk
			}
			case SUCCESS, PARTIAL -> {
				path = path.concat(lookaheadSearch.result());
				lookaheadSearch = null;
			}
			case FAILED -> {
				// Nothing beyond here yet — most often because the chunks out there are still loading.
				// Drop it and let the ordinary end-of-path replan try again from closer up, by which
				// point the terrain will usually have arrived.
				lookaheadSearch = null;
			}
		}
	}

	/** Builds a search from {@code from} to the current goal. */
	private PathFinder newSearch(Minecraft minecraft, LocalPlayer player, BlockPos from,
			Set<Long> favoured) {
		return new PathFinder(
				new WorldView(minecraft.level),
				from,
				goal,
				InventoryManager.snapshot(player),
				allowBreak,
				allowPlace,
				clutchEnabled && InventoryManager.hasWaterBucket(player),
				allowParkour,
				favoured);
	}

	/**
	 * Snapshots the route currently being walked, so the search that replaces it can be biased towards
	 * staying on it. Taken before the plan is torn down, which is the only moment it still exists.
	 */
	private Set<Long> favouredPositions() {
		if (path == null) {
			return Set.of();
		}
		Set<Long> positions = new HashSet<>(path.size());
		for (int index = 0; index < path.size(); index++) {
			positions.add(path.step(index).pos().asLong());
		}
		return positions;
	}

	/**
	 * Whether the player has strayed from the route far enough to throw the plan away.
	 *
	 * <p>Distance is measured to <em>either end</em> of the movement in progress, so being halfway
	 * along it never reads as being lost. Mild drift is tolerated for a while rather than replanned on
	 * the first tick: a shove from a mob or a clipped corner corrects itself, and replanning instantly
	 * turns a wobble into a stutter that never recovers.</p>
	 */
	private boolean isOffPath(LocalPlayer player, Path.Step step) {
		Vec3 position = player.position();
		double distance = Math.min(
				position.distanceTo(Vec3.atBottomCenterOf(step.pos())),
				position.distanceTo(Vec3.atBottomCenterOf(step.from())));

		if (distance > BotSettings.PATH_ABANDON_DISTANCE) {
			ticksOffPath = 0;
			return true;
		}
		if (distance > BotSettings.PATH_DRIFT_DISTANCE) {
			return ++ticksOffPath > BotSettings.MAX_TICKS_OFF_PATH;
		}
		ticksOffPath = 0;
		return false;
	}

	/** Distance of the nearest jump within the next {@code window} steps, or {@code 0}. */
	private int parkourWithin(int window) {
		int limit = Math.min(path.size(), stepIndex + window);
		for (int index = stepIndex; index < limit; index++) {
			int distance = path.step(index).parkourDistance();
			if (distance > 0) {
				return distance;
			}
		}
		return 0;
	}

	/**
	 * Drives a running jump, following Baritone's {@code MovementParkour} state machine.
	 *
	 * <p>Three things make this land reliably where the previous attempt did not, and all three come
	 * from knowing the movement's <em>source</em> as well as its destination:</p>
	 * <ul>
	 *   <li><b>The launch is positional, not timed.</b> The jump fires on the tick the player's centre
	 *   crosses the far face of the launch block — the moment the body is still supported by that
	 *   block's corner but has nothing else ahead of it. That is the furthest-reaching point to leave
	 *   from, and because it is a place rather than an elapsed time it holds at any approach speed.
	 *   The old "fraction of the way through the block" trigger had to be tuned per distance, and was
	 *   wrong for every speed it was not tuned at.</li>
	 *   <li><b>Power matches the gap.</b> Sprint only for the longest jump; everything shorter is a
	 *   walking jump. A sprint carries about four blocks, so sprinting a short gap flies past the
	 *   landing entirely.</li>
	 *   <li><b>There is a run-up.</b> Arriving at the launch block sideways leaves no speed in the jump
	 *   direction, and a jump from a standstill drops short however well it is timed. Rather than jump
	 *   anyway, the bot backs off one block behind the launch block and comes at it straight.</li>
	 * </ul>
	 *
	 * <p>There is deliberately no landing brake. Braking existed to rescue overshoots caused by the
	 * two problems above; with the launch point and the power right, the jump lands on the block, and
	 * a brake would only rob the next jump of the momentum it needs to chain.</p>
	 */
	private void driveParkour(LocalPlayer player, Path.Step jump) {
		BlockPos from = jump.from();
		BlockPos destination = jump.pos();
		BlockPos direction = jump.direction();

		// An ascending jump spends part of its arc climbing, so it always needs the sprint.
		boolean sprint = jump.parkourDistance() >= BotSettings.PARKOUR_SPRINT_MIN_DISTANCE
				|| jump.parkourAscend();

		if (!player.onGround()) {
			// Committed to the arc. Horizontal velocity is already fixed; keep holding jump while
			// rising (releasing early cuts the height short) and keep facing the landing.
			input.jump(player.getDeltaMovement().y > 0.0);
			aimAndRun(player, destination, sprint);
			return;
		}

		BlockPos feet = feetPosition(player);

		// Off the launch line entirely — approached from the side, or knocked about. Back up one block
		// behind the launch block so the approach is straight, then run at it. This is the run-up, and
		// it is the piece that was missing: without it, a jump planned from a corner launched with
		// almost no speed in the jump direction and fell into the gap every time.
		if (!feet.equals(from) && !feet.equals(destination)
				&& !feet.equals(from.offset(direction.getX(), 0, direction.getZ()))) {
			BlockPos runUp = from.subtract(direction);
			aimAndRun(player, feet.equals(runUp) ? from : runUp, false);
			return;
		}

		input.jump(atLaunchLip(player, from, direction));
		aimAndRun(player, destination, sprint);
	}

	/**
	 * Whether the player's centre has reached — or will reach during this tick — the far face of the
	 * launch block.
	 *
	 * <p>Worked out in continuous space from position plus velocity rather than from the feet block
	 * coordinate, because the controller runs before the physics update: testing the block coordinate
	 * alone notices the crossing a tick late, by which point only a sliver of the launch block is
	 * still under the player and the jump may not register at all.</p>
	 */
	private static boolean atLaunchLip(LocalPlayer player, BlockPos from, BlockPos direction) {
		boolean alongX = direction.getX() != 0;
		int sign = alongX ? direction.getX() : direction.getZ();
		double position = alongX ? player.getX() : player.getZ();
		double velocity = alongX ? player.getDeltaMovement().x : player.getDeltaMovement().z;
		double base = alongX ? from.getX() : from.getZ();

		double predicted = position + velocity;
		// How far through the launch block we will be, measured along the direction of travel, where
		// 1.0 is the far face.
		double progress = sign > 0 ? predicted - base : (base + 1.0) - predicted;
		return progress >= 1.0;
	}

	/** Whether this step goes straight up or down a ladder or vine. */
	private boolean isClimbing(Minecraft minecraft, Path.Step step) {
		if (step.pos().getX() != step.from().getX() || step.pos().getZ() != step.from().getZ()) {
			return false; // not a vertical move
		}
		WorldView world = new WorldView(minecraft.level);
		return world.isClimbable(step.from()) || world.isClimbable(step.pos());
	}

	/**
	 * Climbs a ladder or vine.
	 *
	 * <p>Minecraft has no climb key: you go up a ladder by <em>walking into it</em>, which means facing
	 * the wall it is fixed to and holding forward. So this faces the ladder's backing block rather than
	 * the destination — steering at the destination would mean aiming straight up, which yields no
	 * usable heading at all and just spins the view.</p>
	 *
	 * <p>Descending is the same grip with forward released: the player slides down under gravity while
	 * still attached. Sneak holds position on the ladder, so it is deliberately never pressed here.</p>
	 */
	private void driveClimb(Minecraft minecraft, LocalPlayer player, Path.Step step) {
		boolean ascending = step.pos().getY() > step.from().getY();
		WorldView world = new WorldView(minecraft.level);

		// The wall the ladder hangs on. Face that, not the ladder cell itself, or "forward" points
		// along the shaft instead of into it and the player never grips.
		BlockPos ladder = ascending ? step.from() : step.pos();
		BlockPos backing = climbBacking(world, ladder);

		Vec3 facing = backing != null
				? Vec3.atCenterOf(backing)
				: Vec3.atBottomCenterOf(ladder); // vine with no backing found; press into its own cell
		float desiredYaw = Steering.yawTowards(player.position(), facing);
		player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
		player.setXRot(Steering.approach(player.getXRot(), 0.0f));

		input.sprint(false).sneak(false);
		input.forward(ascending);
		// A ladder that starts one block up needs a hop to reach the first rung; on the ladder itself
		// holding forward is enough and jumping only breaks the grip.
		input.jump(ascending && player.onGround() && !world.isClimbable(feetPosition(player)));
	}

	/** The solid block a ladder is fixed to, so the bot knows which way to press. */
	private static BlockPos climbBacking(WorldView world, BlockPos ladder) {
		for (Direction dir : new Direction[] {
				Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST }) {
			BlockPos neighbour = ladder.relative(dir);
			if (world.isKnown(neighbour) && world.isStandable(neighbour)) {
				return neighbour;
			}
		}
		return null;
	}

	/**
	 * Baritone's {@code moveTowards}: face the centre of a block and hold forward.
	 *
	 * <p>Yaw snaps rather than easing. A jump is an aimed action whose whole trajectory is fixed the
	 * instant the player leaves the ground, so there is no time to turn into the heading — unlike
	 * ordinary walking, where easing is what keeps the bot from cutting corners.</p>
	 */
	private void aimAndRun(LocalPlayer player, BlockPos target, boolean sprint) {
		Vec3 centre = Vec3.atBottomCenterOf(target);
		float desiredYaw = Steering.yawTowards(player.position(), centre);
		player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
		player.setXRot(Steering.approach(player.getXRot(), 0.0f));

		float yawError = Steering.angleDifference(player.getYRot(), desiredYaw);
		input.forward(yawError < BotSettings.MAX_FORWARD_ANGLE);
		input.sprint(sprint && !player.isInWater() && yawError < BotSettings.SPRINT_ANGLE_THRESHOLD);
	}

	/**
	 * How far along the route the player has actually got, searching forward only.
	 *
	 * <p>Progress is now an exact "am I standing on this node?" test, which is Baritone's rule and is
	 * only correct because the smoother is gone: with every node walked through rather than cut past,
	 * the feet block <em>is</em> the answer, and it needs no tolerance to tune. Scanning a few nodes
	 * ahead as well as the current one recovers cleanly from a fall or a shove that skipped some.</p>
	 *
	 * <p>Forward-only, so being pushed backwards shows up as a growing distance and trips the off-path
	 * check rather than silently rewinding our progress.</p>
	 */
	private int advanceProgress(Minecraft minecraft, LocalPlayer player) {
		BlockPos feet = feetPosition(player);
		int limit = Math.min(path.size() - 1, stepIndex + BotSettings.MAX_LOOKAHEAD_STEPS);

		for (int index = stepIndex; index <= limit; index++) {
			Path.Step step = path.step(index);
			if (index > stepIndex && isWorkOutstanding(minecraft, step)) {
				break; // never let progress run past mining or building we have not done yet
			}
			if (feet.equals(step.pos())) {
				return index + 1; // standing on it: it is behind us, head for the next one
			}
		}

		// No exact match. A partial block underfoot, or the arc of a jump, can leave the feet block a
		// little off the node we are plainly standing at — so fall back to proximity for the node we
		// are currently heading for.
		double arrival = BotSettings.NODE_ARRIVAL_DISTANCE * BotSettings.NODE_ARRIVAL_DISTANCE;
		double distance = player.position().distanceToSqr(
				Vec3.atBottomCenterOf(path.step(stepIndex).pos()));
		return distance < arrival ? stepIndex + 1 : stepIndex;
	}

	/**
	 * Whether this step's world edits still need doing, judged against the world as it is now.
	 *
	 * <p>Deliberately not {@code Step.needsWork()}: that reports what the <em>plan</em> called for
	 * and stays true forever once satisfied. Blocking progress on it strands the bot on a node it
	 * has already finished — which, for a pillar, means standing on the block it just placed and
	 * steering at its own feet.</p>
	 */
	private boolean isWorkOutstanding(Minecraft minecraft, Path.Step step) {
		for (BlockPos blocking : step.toBreak()) {
			if (!minecraft.level.getBlockState(blocking).isAir()) {
				return true;
			}
		}
		return step.toPlace() != null && needsFilling(minecraft, step.toPlace());
	}

	private void tickBreaking(Minecraft minecraft, LocalPlayer player) {
		switch (breaker.tick(minecraft, player)) {
			case WORKING -> {
				// stand still and keep mining
			}
			case DONE -> {
				breakIndex++;
				status = Status.FOLLOWING;
			}
			case OUT_OF_RANGE, FAILED -> {
				breaker.cancel(minecraft);
				replan(minecraft);
			}
		}
	}

	/** Breaking the block we came to mine. */
	private void tickMining(Minecraft minecraft, LocalPlayer player) {
		switch (breaker.tick(minecraft, player)) {
			case WORKING -> {
				// keep mining
			}
			case DONE -> beginCollecting(minecraft,
					mineTarget != null ? Vec3.atCenterOf(mineTarget) : player.position());
			case OUT_OF_RANGE, NO_MATERIAL, FAILED -> {
				breaker.cancel(minecraft);
				// Too far to touch from here. Aim the route at the block itself so the pathfinder
				// digs its way in, rather than replanning to the spot we already stand on.
				if (mineTarget != null) {
					goal = new GoalBlock(mineTarget);
				}
				replan(minecraft);
			}
		}
	}

	/**
	 * Starts sweeping up whatever the block we just broke left behind.
	 *
	 * <p>Mining is pointless if the results stay on the floor, and a hunt that moves straight to the
	 * next target walks away from its own drops every time. Baritone does the same thing from the
	 * other direction — {@code mineScanDroppedItems} makes dropped items of the wanted type valid
	 * pathing destinations in their own right.</p>
	 */
	/**
	 * @param anchor where the drops should be — the block that broke. Loot
	 *               is only swept near this point, so the bot collects what <em>it</em> produced
	 *               rather than every stray item it happens to walk past.
	 */
	private void beginCollecting(Minecraft minecraft, Vec3 anchor) {
		resetPlan(minecraft);
		collecting = true;
		collectAnchor = anchor;
		collectTicks = 0;
		status = Status.COLLECTING;
	}

	/** Nearest drop belonging to the block we just mined, or {@code null}. */
	private ItemEntity findOwnDrop(Minecraft minecraft) {
		if (collectAnchor == null) {
			return null;
		}
		return ItemScanner.findNear(
				minecraft.level, collectAnchor, BotSettings.COLLECT_RADIUS, stack -> true);
	}

	/**
	 * Waits for the drops to appear, then hands each one to the normal navigation machinery.
	 *
	 * <p>This state only covers the gap between the block breaking and the drops existing. Actually getting
	 * there is a routing problem — the loot may be behind the wall we just mined through, at the
	 * bottom of a hole, or across a fence — so it becomes an ordinary goal rather than something
	 * walked at blindly.</p>
	 */
	private void tickCollecting(Minecraft minecraft, LocalPlayer player) {
		collectTicks++;

		ItemEntity drop = findOwnDrop(minecraft);

		if (drop == null) {
			// Drops appear a tick or two after the block breaks, so an instant "nothing here"
			// would always be wrong. Wait briefly before concluding there is nothing.
			if (collectTicks > BotSettings.COLLECT_GRACE_TICKS) {
				collecting = false;
				continueHunt(minecraft);
			}
			return;
		}

		goal = new GoalBlock(BlockPos.containing(drop.position()));
		replan(minecraft); // routes there properly, obstacles and all
	}

	/** Called on arrival while sweeping loot: take the next drop, or finish and move on. */
	private void resumeCollecting(Minecraft minecraft, LocalPlayer player) {
		ItemEntity drop = findOwnDrop(minecraft);

		if (drop == null) {
			collecting = false;
			continueHunt(minecraft);
			return;
		}
		goal = new GoalBlock(BlockPos.containing(drop.position()));
		replan(minecraft);
	}

	/** Abandons the loot sweep — used when the drops turn out to be unreachable. */
	private void abandonCollecting(Minecraft minecraft) {
		collecting = false;
		message("Can't reach the drops — moving on.");
		continueHunt(minecraft);
	}

	/** Looks for the next target of the hunted kind, finishing the hunt when none are left. */
	private void continueHunt(Minecraft minecraft) {
		Block block = huntedBlock;
		EntityType<?> type = huntedType;
		String name = huntedName;
		boolean execute = huntExecute;
		resetPlan(minecraft);

		LocalPlayer player = minecraft.player;
		boolean more = player != null && execute && (block != null
				? huntFor(minecraft, player, block, name, true)
				: type != null && huntForEntity(minecraft, player, type, name, true));

		if (!more) {
			huntedBlock = null;
			huntedType = null;
			huntedEntity = null;
			mineTarget = null;
			status = Status.SUCCEEDED;
			input.clear();
		}
	}

	private void tickPlacing(Minecraft minecraft, LocalPlayer player) {
		ActionState result = placer.tick(minecraft, player, input);
		switch (result) {
			case WORKING -> {
				// keep clicking
			}
			case DONE -> status = Status.FOLLOWING;
			case OUT_OF_RANGE -> {
				placer.cancel();
				replan(minecraft);
			}
			case NO_MATERIAL -> {
				placer.cancel();
				// Escalate: loot lying about first (free), then dig some up, and only give up on
				// building when there is nothing worth mining either. The dropped-block search
				// excludes the haul — chasing the spruce log we just dropped, only to refuse to
				// build with it, is an infinite loop.
				Block avoid = huntedBlock;
				if (fetchNearby(minecraft, player,
						stack -> InventoryManager.isBuildingBlockExcept(stack, avoid),
						"building blocks")) {
					replan(minecraft);
				} else {
					// Baritone's behaviour when it runs out of throwaway blocks: stop building and
					// route around instead. It does not go off to dig up more, and neither do we.
					allowPlace = false;
					message("Nothing to build with nearby — continuing without placing.");
					replan(minecraft);
				}
			}
			case FAILED -> {
				// A bad anchor or a timeout, not an inventory problem. Replanning routes around it
				// and keeps building available for the rest of the journey.
				placer.cancel();
				replan(minecraft);
			}
		}
	}

	// ---------------------------------------------------------------- movement

	/**
	 * Steers towards one path node, jumping and sprinting as the terrain calls for it.
	 *
	 * <p>Purely a steering function: it decides which keys to hold and which way to face, and never
	 * touches the progress marker. Progress is owned solely by {@link #advanceProgress}.</p>
	 */
	private void walkTowards(Minecraft minecraft, LocalPlayer player, Path.Step step) {
		Vec3 position = player.position();
		Vec3 target = Vec3.atBottomCenterOf(step.pos());
		double heightDelta = target.y - position.y;
		double dx = target.x - position.x;
		double dz = target.z - position.z;
		boolean hasHeading = dx * dx + dz * dz > BotSettings.YAW_DEADZONE_SQR;

		// Ease the pitch back to level after mining or building looked up/down.
		player.setXRot(Steering.approach(player.getXRot(), 0.0f));

		if (hasHeading) {
			float desiredYaw = Steering.yawTowards(position, target);
			player.setYRot(Steering.approach(player.getYRot(), desiredYaw, BotSettings.NAV_TURN_PER_TICK));
			float yawError = Steering.angleDifference(player.getYRot(), desiredYaw);

			// Turn on the spot rather than walking off at a wild angle and swinging back.
			input.forward(yawError < BotSettings.MAX_FORWARD_ANGLE);

			input.sprint(shouldSprint(minecraft, player)
					&& yawError < BotSettings.SPRINT_ANGLE_THRESHOLD);
		} else {
			// Directly above or below us: there is no meaningful heading to take, and computing one
			// from the near-zero horizontal delta would just spin the view on the spot.
			input.forward(false);
		}

		// Once genuinely falling, stop steering: further input only adds drift, and drifting is how
		// a planned three-block drop turns into an unplanned six-block one.
		if (!player.onGround() && player.getDeltaMovement().y < -0.4) {
			input.forward(false).sprint(false);
		}

		if (player.isInWater()) {
			// Swimming: hold jump to stay up, and to climb out at the far side.
			input.jump(heightDelta > -0.2);
		} else if (heightDelta > 0.4 && player.onGround()) {
			input.jump(true);
		} else if (player.horizontalCollision && player.onGround()) {
			// Something low is in the way that the node grid does not model — a snow layer, a slab,
			// a fence post. Hop it rather than grinding into it.
			input.jump(true);
		} else if (ticksSinceProgress > STUCK_JUMP_TICKS && player.onGround()) {
			// Nudge over a small lip (fence post, uneven slab) before declaring failure.
			input.jump(true);
		}
	}

	/**
	 * Whether to sprint into the current step — Baritone's rule: only when the move after it carries
	 * on in the same direction.
	 *
	 * <p>Sprinting is not a speed setting, it is a commitment: it roughly doubles the momentum the bot
	 * carries into whatever comes next. Spend it on a straight run and it is free speed; spend it into
	 * a turn and the bot swings wide of the corner, and into a ledge and it launches off the drop and
	 * lands well past the column it meant to descend. Gating on the direction of the <em>next</em> move
	 * is what makes that judgement, and it replaces a "sprint whenever a few nodes remain" heuristic
	 * that knew nothing about the shape of the route and flickered on and off as the count changed.</p>
	 */
	private boolean shouldSprint(Minecraft minecraft, LocalPlayer player) {
		if (player.isInWater()) {
			return false;
		}
		// A jump coming up overrides the corner rule: its run-up is decided by the jump's own length,
		// because the long jump has to arrive at the launch block already at full speed, and the short
		// one must arrive slower or it overshoots the landing.
		int jump = parkourWithin(BotSettings.PARKOUR_RUNWAY_STEPS);
		if (jump > 0) {
			return jump >= BotSettings.PARKOUR_SPRINT_MIN_DISTANCE;
		}
		if (stepIndex + 1 >= path.size()) {
			return false; // nowhere left to sprint to; charging past the last node means doubling back
		}

		Path.Step current = path.step(stepIndex);
		Path.Step next = path.step(stepIndex + 1);
		if (next.needsWork()) {
			return false; // we are about to stop and mine or build; arriving fast helps nothing
		}
		if (next.pos().getY() < current.pos().getY()) {
			return false; // a drop is coming: do not carry momentum over the edge
		}
		if (isEdging(minecraft, current) || isEdging(minecraft, next)) {
			return false; // squeezing past a corner; vanilla would cancel the sprint on contact anyway
		}
		return isCollinear(current, next);
	}

	/**
	 * Whether a diagonal step squeezes past a blocked corner rather than crossing open ground.
	 *
	 * <p>The planner now accepts these — a player does slide diagonally along a wall face — but they
	 * are not worth sprinting: brushing the wall cancels the sprint in vanilla regardless, and going
	 * into the squeeze at speed is what wedges the bot on the corner.</p>
	 */
	private boolean isEdging(Minecraft minecraft, Path.Step step) {
		int dx = step.pos().getX() - step.from().getX();
		int dz = step.pos().getZ() - step.from().getZ();
		if (dx == 0 || dz == 0) {
			return false; // not a diagonal; there is no corner to squeeze past
		}
		WorldView world = new WorldView(minecraft.level);
		return !world.fitsAt(step.from().offset(dx, 0, 0))
				|| !world.fitsAt(step.from().offset(0, 0, dz));
	}

	/** Whether two consecutive steps continue in the same horizontal direction. */
	private static boolean isCollinear(Path.Step first, Path.Step second) {
		return first.pos().getX() - first.from().getX() == second.pos().getX() - second.from().getX()
				&& first.pos().getZ() - first.from().getZ() == second.pos().getZ() - second.from().getZ();
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Watches for the bot failing to advance along its route, and replans when it does.
	 *
	 * <p>Progress is the furthest path node reached — not distance travelled. A bot circling on the
	 * spot, shuffling against a wall, or repeatedly failing a jump is moving continuously while
	 * getting nowhere, so a movement-based test stays silent exactly when it is needed most.</p>
	 */
	private void trackProgress(Minecraft minecraft, LocalPlayer player) {
		if (status != Status.FOLLOWING) {
			// Breaking and building legitimately make no path progress; don't count that as stuck.
			ticksSinceProgress = 0;
			return;
		}
		if (stepIndex > furthestProgress) {
			furthestProgress = stepIndex;
			ticksSinceProgress = 0;
			return;
		}
		if (++ticksSinceProgress > movementBudget()) {
			handleStuck(minecraft, player);
		}
	}

	/**
	 * Ticks the current movement is allowed before it counts as stuck: what the planner estimated it
	 * would take, plus a fixed grace period.
	 *
	 * <p>Budgeting per movement rather than against one global timeout is Baritone's approach, and the
	 * difference is real. A flat limit has to be generous enough for the slowest legitimate move —
	 * tunnelling through stone, a long fall — which leaves it far too patient with the common failure,
	 * a bot wedged against a fence post on a one-block walk. Scaling with the estimate keeps quick
	 * moves on a short leash and slow ones unhurried.</p>
	 */
	private double movementBudget() {
		double estimate = path != null && stepIndex < path.size() ? path.step(stepIndex).cost() : 0.0;
		return estimate + BotSettings.MOVEMENT_TIMEOUT_TICKS;
	}

	/**
	 * Replans after getting stuck, giving up if repeated replans are not bringing us any closer.
	 *
	 * <p>The distance check is what stops an infinite loop: replanning from the same spot yields
	 * the same path and the bot gets stuck at the same place. Requiring measurable progress towards
	 * the goal between snags means a genuinely unreachable target eventually reports failure rather
	 * than retrying forever.</p>
	 */
	private void handleStuck(Minecraft minecraft, LocalPlayer player) {
		// Loot is optional. Never let an unreachable drop stall the hunt behind it.
		if (collecting) {
			abandonCollecting(minecraft);
			return;
		}

		// Before replanning, try to chew through whatever is physically in the way. The grid model
		// misjudges partial and awkward blocks — a snowed-over leaf at head height, a fence, a
		// half-slab — so the planner keeps producing a route the physics cannot walk, and the bot
		// bonks and replans forever. Breaking the obstruction is what actually gets it moving.
		if (allowBreak && breakObstruction(minecraft, player)) {
			return;
		}

		// Progress is measured with the goal's *own* estimate rather than as a distance to a point. For
		// an exact block those agree; for a column or a height they do not, and a plain distance would
		// count the bot's changing altitude as drifting away from an X/Z goal it was walking straight
		// towards — and then give up on it.
		double distance = goal.heuristic(feetPosition(player));

		if (distance < bestGoalDistance - BotSettings.STUCK_PROGRESS_MARGIN) {
			bestGoalDistance = distance;
			fruitlessReplans = 0;
		} else if (++fruitlessReplans >= BotSettings.MAX_STUCK_REPLANS) {
			fail(minecraft, "Stuck and not getting closer to " + describeGoal() + " — giving up.");
			return;
		}

		message("Stuck — recalculating route.");
		replan(minecraft);
	}

	/**
	 * Breaks the block obstructing forward movement, if there is a breakable one.
	 *
	 * <p>Looks at the block ahead in the direction of the current path node, at both foot and head
	 * height, and mines the first breakable one. This is the bot's escape hatch from geometry the
	 * pathfinder cannot model: rather than declaring failure at a leaf it keeps clipping, it opens
	 * a hole and walks through.</p>
	 *
	 * @return whether a break was started
	 */
	private boolean breakObstruction(Minecraft minecraft, LocalPlayer player) {
		if (path == null || stepIndex >= path.size()) {
			return false;
		}
		Vec3 toNode = Vec3.atBottomCenterOf(path.step(stepIndex).pos()).subtract(player.position());
		Direction dir = Direction.getApproximateNearest(toNode.x, 0.0, toNode.z);

		WorldView world = new WorldView(minecraft.level);
		BlockPos feet = feetPosition(player);
		// Head first: a head-height leaf is the exact case that traps the bot while its feet are
		// clear, so clearing the head opens the way even when the foot block is already passable.
		for (BlockPos ahead : new BlockPos[] { feet.above().relative(dir), feet.relative(dir) }) {
			if (world.isKnown(ahead) && !world.state(ahead).isAir() && world.isBreakable(ahead)) {
				resetPlan(minecraft);
				breaker.begin(ahead);
				status = Status.BREAKING;
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the journey is done.
	 *
	 * <p>Two tests, deliberately. The goal's own {@link Goal#isInGoal} is the authority — it is the
	 * only thing that knows what an X/Z goal or a radius goal actually means. The distance tolerance
	 * beneath it is a safety net for the exact-block case: standing at a block boundary, or on a slab
	 * that shifts the feet coordinate, can leave the bot visibly arrived while its feet block reads as
	 * the neighbour, and without the fallback it would circle the destination forever.</p>
	 */
	private boolean withinGoalTolerance(LocalPlayer player) {
		if (goal.isInGoal(feetPosition(player))) {
			return true;
		}
		Vec3 target = Vec3.atBottomCenterOf(goal.approximatePosition());
		Vec3 position = player.position();
		double dx = target.x - position.x;
		double dz = target.z - position.z;
		double dy = target.y - position.y;
		return dx * dx + dz * dz < BotSettings.GOAL_TOLERANCE * BotSettings.GOAL_TOLERANCE
				&& Math.abs(dy) < BotSettings.GOAL_VERTICAL_TOLERANCE;
	}

	/**
	 * Whether a block still has to be placed at {@code pos} for the plan to hold. Delegates to
	 * {@link WorldView#isFillable(net.minecraft.world.level.BlockGetter, BlockPos)} so the planner,
	 * the placer and this controller all judge "already filled?" by one rule and cannot drift apart.
	 */
	private boolean needsFilling(Minecraft minecraft, BlockPos pos) {
		return WorldView.isFillable(minecraft.level, pos);
	}

	/** Block position of the player's feet — the coordinate space the pathfinder works in. */
	private static BlockPos feetPosition(LocalPlayer player) {
		return BlockPos.containing(player.position());
	}

	/**
	 * Where the search should treat the player as standing.
	 *
	 * <p>On the ground this is just the feet block. In the air — a long fall the airborne wait gave
	 * up on — it snaps down to the first standable block, so the plan still starts from real ground
	 * rather than a point in the sky the physics will never leave the player at.</p>
	 */
	private BlockPos planStart(Minecraft minecraft, LocalPlayer player) {
		BlockPos feet = feetPosition(player);
		if (player.onGround() || player.isInWater()) {
			return feet;
		}
		WorldView world = new WorldView(minecraft.level);
		for (int drop = 0; drop <= BotSettings.MAX_FALL_SCAN; drop++) {
			BlockPos candidate = feet.below(drop);
			if (world.isKnown(candidate.below()) && world.isStandable(candidate.below())
					&& world.fitsAt(candidate)) {
				return candidate;
			}
		}
		return feet;
	}

	private void replan(Minecraft minecraft) {
		// Snapshot the outgoing route first: the search that replaces it discounts these positions, so
		// a replan nudges the current course rather than proposing an unrelated one.
		favouredRoute = favouredPositions();
		resetPlan(minecraft);
		status = Status.PLANNING;
	}

	private void resetPlan(Minecraft minecraft) {
		path = null;
		search = null;
		lookaheadSearch = null;
		stepIndex = 0;
		breakIndex = 0;
		ticksSinceProgress = 0;
		furthestProgress = -1;
		airborneWait = 0;
		ticksOffPath = 0;
		if (minecraft != null) {
			breaker.cancel(minecraft);
		}
		placer.cancel();
	}

	/**
	 * Detours to a dropped stack of something we have run out of.
	 *
	 * @return whether a detour was started; if not, the caller should fall back to doing without
	 */
	private boolean fetchNearby(Minecraft minecraft, LocalPlayer player,
			Predicate<ItemStack> wanted, String what) {
		if (resumeGoal != null) {
			return false; // already fetching something; don't stack detours
		}
		var dropped = ItemScanner.findNearby(
				minecraft.level, player, wanted, BotSettings.ITEM_SEARCH_RADIUS);
		if (dropped == null) {
			return false;
		}

		resumeGoal = goal;
		goal = new GoalBlock(BlockPos.containing(dropped.position()));
		message("Out of " + what + " — collecting some from " + describeGoal() + ".");
		return true;
	}

	private void succeed(Minecraft minecraft) {
		// Finishing a fetch detour is not finishing the journey: pick the real goal back up.
		if (resumeGoal != null) {
			goal = resumeGoal;
			resumeGoal = null;
			// We may well have what we were missing now, so allow building again.
			allowPlace = allowPlaceRequested;
			message("Collected — resuming to " + describeGoal() + ".");
			replan(minecraft);
			return;
		}

		// Reached a drop. Pickup is automatic on contact, so look for the next one.
		if (collecting && minecraft.player != null) {
			resumeCollecting(minecraft, minecraft.player);
			return;
		}

		// Still chasing a live mob. Reaching where it stood is not the end of anything — re-aim at
		// wherever it has got to and keep going.
		if (huntedEntity != null && huntedEntity.isAlive()) {
			goal = new GoalBlock(BlockPos.containing(huntedEntity.position()));
			replan(minecraft);
			return;
		}

		if (mineTarget != null && minecraft.player != null) {
			// Arriving does not guarantee the block is gone: we deliberately stop *beside* it, and
			// even routing through it can leave a no-collision block like a mushroom untouched.
			// Break it explicitly before moving on.
			if (mineTargetBlock != null
					&& minecraft.level.getBlockState(mineTarget).is(mineTargetBlock)) {
				resetPlan(minecraft);
				breaker.begin(mineTarget);
				status = Status.MINING;
				return;
			}
			continueHunt(minecraft);
			return;
		}

		if (huntedType != null && huntExecute) {
			continueHunt(minecraft);
			return;
		}

		resetPlan(minecraft);
		status = Status.SUCCEEDED;
		input.clear();
		message("Arrived at " + describeGoal() + ".");
	}

	private void fail(Minecraft minecraft, String reason) {
		resetPlan(minecraft);
		status = Status.FAILED;
		input.clear();
		message(reason);
	}

	private void message(String text) {
		messageSink.accept(Component.literal("[mcbot] " + text));
	}

	private static String format(BlockPos pos) {
		return pos == null ? "?" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

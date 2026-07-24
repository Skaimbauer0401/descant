package mcbot.client;

/**
 * Central tunables for the bot.
 *
 * <p>Every magic number the pathfinder or the executor relies on lives here so behaviour can be
 * adjusted in one place instead of hunting through the algorithm. Costs are expressed in
 * <b>game ticks</b> — the estimated time a movement takes — which keeps the A* cost function and
 * the heuristic in the same unit and makes them directly comparable.</p>
 */
public final class BotSettings {

	private BotSettings() {
	}

	// ---------------------------------------------------------------- movement costs (ticks)

	/**
	 * Time to cross one block while sprinting. Also the A* heuristic scale.
	 *
	 * <p>{@code 20 / 5.612} — twenty ticks per second divided by the sprint speed in blocks per
	 * second. These are Baritone's numbers, derived from the vanilla movement constants rather than
	 * eyeballed, which matters because the ratios between them are what the planner actually weighs:
	 * a swim priced slightly wrong is a bot that dives, and a jump priced slightly wrong is a bot
	 * that bridges a gap it could have hopped.</p>
	 */
	public static final double SPRINT_COST = 3.564;

	/** Time to cross one block while walking ({@code 20 / 4.317}). */
	public static final double WALK_COST = 4.633;

	/** Diagonal movement covers sqrt(2) blocks. */
	public static final double DIAGONAL_MULTIPLIER = Math.sqrt(2.0);

	/** Extra cost for gaining a block of height. Jumping is slow and interrupts sprinting. */
	public static final double JUMP_COST = 5.0;

	/**
	 * Height a player can walk up without jumping. Above this a move needs a jump; above
	 * {@link #MAX_JUMP_HEIGHT} it is not possible at all.
	 */
	public static final double STEP_HEIGHT = 0.6;

	/**
	 * The highest a player can jump, unaided.
	 *
	 * <p>Checked against the <em>real</em> surface heights rather than block coordinates, because
	 * partial blocks make those disagree. Stepping onto a snow-covered block looks like an ordinary
	 * one-block hop on the grid, but the snow adds up to 0.875 on top — a 1.875 climb that no jump
	 * will ever complete, so the bot would wedge against it retrying.</p>
	 */
	public static final double MAX_JUMP_HEIGHT = 1.25;

	/** Extra cost per block of falling. Cheap — falling is fast — but not free, to avoid churn. */
	public static final double FALL_COST_PER_BLOCK = 1.0;

	/** Swimming is roughly half walking speed ({@code 20 / 2.2}). */
	public static final double SWIM_COST = 9.091;

	/** Time to climb one block of ladder or vine ({@code 20 / 2.35}). */
	public static final double LADDER_UP_COST = 8.511;

	/** Time to descend one block of ladder or vine ({@code 20 / 3.0}) — gravity helps. */
	public static final double LADDER_DOWN_COST = 6.667;

	/**
	 * Flat surcharge on any jump, whether a step up or a gap cleared.
	 *
	 * <p>Baritone's {@code jumpPenalty}, and deliberately small: a jump really does cost only a
	 * couple of ticks of lost ground speed. Its job is to break ties in favour of not jumping, not to
	 * discourage jumping — pricing it high is what makes a bot bridge across a one-block gap it could
	 * have hopped in a quarter of the time.</p>
	 */
	public static final double JUMP_PENALTY = 2.0;

	/** Flat penalty added on top of the estimated mining time, covering aim + re-approach. */
	public static final double BREAK_OVERHEAD = 6.0;

	/** Cost of placing one support block (aim, sneak, click, re-approach). */
	public static final double PLACE_COST = 20.0;

	/**
	 * Furthest a sprint-jump may land, measured in blocks along a cardinal from the launch block.
	 *
	 * <p>2 is a one-block gap (trivial), 3 a two-block gap (reliable with a running start), 4 a
	 * three-block gap (the classic edge-of-the-block jump — possible, but wants good timing, so it is
	 * priced highest and only taken when there is nothing shorter).</p>
	 */
	public static final int MAX_PARKOUR_DISTANCE = 4;

	/**
	 * Surcharge per gap cell on a jump, when the bot could bridge that gap instead.
	 *
	 * <p>Bridging is the preferred way across a gap: it is slower and spends blocks, but it leaves
	 * solid ground behind and cannot drop the bot into a ravine. A jump has no such failure floor —
	 * missing one costs the fall.</p>
	 *
	 * <p>The number is derived rather than guessed. Bridging one cell costs {@link #PLACE_COST} plus a
	 * step across it ({@link #WALK_COST}), about 24.6 ticks; a jump crosses that same cell for roughly
	 * {@code WALK_COST} of its own price, so the gap between the two options is about 20 ticks per
	 * cell. Anything above that tips the choice to bridging; this leaves a clear margin on top.</p>
	 *
	 * <p>Applied only when placing is allowed. When it is not — {@code /mcbot walk}, or having run out
	 * of blocks — there is nothing to prefer, so jumps go back to their honest price rather than
	 * becoming a reason to detour for twenty blocks.</p>
	 */
	public static final double PARKOUR_BRIDGE_SURCHARGE = 22.0;

	/**
	 * Furthest jump that may also gain a block of height.
	 *
	 * <p>An ascending jump spends part of its arc climbing, so it reaches less far. Baritone allows it
	 * up to three blocks out and always sprints it; four-block ascending jumps are not reliably
	 * possible in vanilla and are not generated.</p>
	 */
	public static final int MAX_PARKOUR_ASCEND_DISTANCE = 3;

	// ---------------------------------------------------------------- pathfinding limits

	/** Hard cap on expanded nodes per search. Prevents unbounded searches in open worlds. */
	public static final int MAX_NODES = 20_000;

	/**
	 * Nanoseconds of A* work allowed per client tick. The search is time-sliced across ticks so a
	 * long search never stalls the render thread — 3 ms of a 50 ms tick is invisible.
	 */
	public static final long SEARCH_BUDGET_NANOS = 3_000_000L;

	/**
	 * Cost multiplier applied to positions on the route being replaced.
	 *
	 * <p>Baritone's {@code backtrackCostFavoringCoefficient}. Two ways around an obstacle are usually
	 * near-identical in cost, so without a bias each replan is free to prefer the other one and the bot
	 * oscillates between them. Halving the cost of the incumbent route makes a replan behave like a
	 * correction to the current plan rather than an unrelated second opinion.</p>
	 */
	public static final double BACKTRACK_FAVOUR = 0.5;

	/**
	 * Estimated ticks left on the current route below which the next segment starts being planned,
	 * while the bot keeps walking.
	 *
	 * <p>A partial path used to end with the bot standing still in {@code PLANNING} until the next
	 * search finished. Overlapping the search with the walk removes that stall entirely: roughly seven
	 * seconds of route is enough runway for a search to complete before it is needed.</p>
	 */
	public static final double PLANNING_TICK_LOOKAHEAD = 150.0;

	/** Vertical scan limit when looking for ground below a candidate step. */
	public static final int MAX_FALL_SCAN = 20;

	/**
	 * Falls deeper than this need water at the bottom or a bucket in hand.
	 *
	 * <p>Vanilla fall damage is {@code floor(distance) - 3}, so three blocks is the exact boundary
	 * of a free drop.</p>
	 */
	public static final int SAFE_FALL_DISTANCE = 3;

	/**
	 * Extra cost for a drop needing a water-bucket clutch. Deliberately enormous: a clutch is an
	 * escape hatch, not a travel technique. At this price the bot will happily bridge a gap fifteen
	 * blocks wide rather than drop down it, and only takes the fall when there is no route at all.
	 */
	public static final double CLUTCH_COST = 400.0;

	/** Deepest drop the bot will take even with a bucket, as a sanity limit. */
	public static final int MAX_CLUTCH_FALL = 80;

	// ---------------------------------------------------------------- survival

	/** Downward speed (blocks/tick) past which a fall is considered committed. */
	public static final double CLUTCH_MIN_FALL_SPEED = 0.5;

	/**
	 * Blocks the player must have already fallen before a clutch is even considered.
	 *
	 * <p>Without this the clutch fires during ordinary hops — a pillar jump drops a quarter of a
	 * block on the way back down, and a mis-timed ground raycast past the edge of a one-wide pillar
	 * then reports a huge drop below. Requiring a real fall first makes the trigger unambiguous.</p>
	 */
	public static final double CLUTCH_MIN_FALL_DISTANCE = 2.5;

	/**
	 * Blocks of predicted fall <em>beyond</em> the safe limit before a clutch is worth it.
	 *
	 * <p>Without a margin the trigger fires on ordinary safe descents: at 2.6 blocks fallen with
	 * 0.6 still to go, the total just tips past three and the bot dumps water on a landing it would
	 * have walked away from.</p>
	 */
	public static final double CLUTCH_DAMAGE_MARGIN = 2.0;

	/** Minimum remaining drop for a clutch to be worth starting — below this there is no time. */
	public static final double CLUTCH_MIN_REMAINING_DROP = 2.0;

	/** How closely the player must sit to the middle of a column before pillaring upward. */
	public static final double PILLAR_CENTRE_TOLERANCE = 0.18;

	/**
	 * Fraction of the inventory that must be occupied before the bot breaks off to bank the haul.
	 *
	 * <p>Not 1.0 deliberately. Waiting for the very last slot means the trip only starts once drops are
	 * already being left on the ground, and a stack that partially merges can fill the remainder in a
	 * single block break. Leaving a few slots spare covers the walk back to the chest.</p>
	 */
	public static final double DEPOSIT_FULLNESS = 0.85;

	/** How far around the player {@code /mcbot chest} looks for a container to remember. */
	public static final int CHEST_SEARCH_RADIUS = 16;

	/** Radius searched for dropped items when the bot runs out of something it needs. */
	public static final double ITEM_SEARCH_RADIUS = 50.0;

	/**
	 * How far from the kill or the broken block loot is looked for.
	 *
	 * <p>Measured from that spot, not from the player, and kept tight: drops scatter a block or two
	 * and no further. A generous radius centred on the player turns a loot sweep into hoovering up
	 * everything in the area, which is not what was asked for.</p>
	 */
	public static final double COLLECT_RADIUS = 5.0;

	/**
	 * Ticks to keep looking before deciding nothing dropped. Drops do not appear on the same tick
	 * the block breaks or the mob dies, so an immediate "nothing here" would always be wrong.
	 */
	public static final int COLLECT_GRACE_TICKS = 12;

	/** Ticks before giving up on loot that cannot be reached, so a hunt is never stalled by it. */
	public static final int COLLECT_TIMEOUT_TICKS = 140;

	/**
	 * Radius searched when hunting for a block type.
	 *
	 * <p>Bounded in practice by what the client has loaded, so raising it past the render distance
	 * costs nothing and finds nothing extra. It is affordable because the search rejects whole 16³
	 * sections on a palette check and stops expanding once a match is found — a common block exits
	 * on the first ring, and a rare one skips almost every section without reading a block.</p>
	 */
	public static final int BLOCK_SEARCH_RADIUS = 320;

	/** Radius searched when hunting for a mob. Entities only exist client-side when nearby. */
	public static final double ENTITY_SEARCH_RADIUS = 192.0;

	/**
	 * Squared distance a pursued mob may drift from the current goal before the route is re-aimed.
	 * Too small and the bot replans every tick as the mob shuffles; too large and it walks to where
	 * the mob used to be.
	 */
	public static final double RETARGET_DISTANCE_SQR = 9.0;

	/** Node budget for the search that finds the nearest breathable space. */
	public static final int AIR_SEARCH_NODES = 800;

	/**
	 * Height above the ground at which the clutch water goes down. Far enough that the placement
	 * has landed before impact, close enough that the bot cannot drift out of its own water.
	 */
	public static final double CLUTCH_TRIGGER_DISTANCE = 4.5;

	/** Air supply (of 300) below which surfacing takes priority over everything else. */
	public static final int AIR_CRITICAL = 120;

	/** Blocks the bot may mine through in a single move (e.g. head + feet of a wall). */
	public static final int MAX_BREAK_PER_MOVE = 2;

	// ---------------------------------------------------------------- execution

	/**
	 * Distance at which a path node counts as entered, so progress moves to the following node.
	 * Generous enough that a sprinting player never skims past a node without registering it.
	 */
	public static final double NODE_ARRIVAL_DISTANCE = 0.6;

	/**
	 * Degrees per tick the <em>walk</em> heading may turn. Movement direction follows the yaw, so
	 * turning it gradually keeps the bot from overshooting corners and walking off edges. Only the
	 * navigation steering is eased like this; aiming at a block or mob still snaps instantly.
	 */
	public static final float NAV_TURN_PER_TICK = 22.0f;

	/** Only sprint when heading this close (degrees) to the desired direction. */
	public static final float SPRINT_ANGLE_THRESHOLD = 35.0f;

	/**
	 * Beyond this heading error the bot turns on the spot instead of walking. Holding forward while
	 * facing the wrong way just walks away from the path and swings back — the "orbiting" look.
	 */
	public static final float MAX_FORWARD_ANGLE = 90.0f;

	/**
	 * Sprinting needs somewhere to sprint to. Below this many nodes remaining the bot walks, which
	 * stops it charging past the final node and having to double back.
	 */
	public static final int MIN_STEPS_FOR_SPRINT = 3;

	/**
	 * Distance from the route at which the plan is abandoned outright and replanned.
	 *
	 * <p>Measured to <em>either end</em> of the movement being executed, so being partway along it is
	 * never mistaken for having wandered off.</p>
	 */
	public static final double PATH_ABANDON_DISTANCE = 3.0;

	/**
	 * Distance from the route that counts as drifting rather than leaving it.
	 *
	 * <p>Between this and {@link #PATH_ABANDON_DISTANCE} the bot is given {@link #MAX_TICKS_OFF_PATH}
	 * ticks to recover before the plan is thrown away. Baritone tolerates drift like this rather than
	 * replanning on the first tick of it, because being shoved a block sideways by a mob or clipping a
	 * corner is normal and self-correcting — replanning instantly turns a wobble into a stutter, and
	 * a stutter next to a wall into a bot that never gets anywhere.</p>
	 */
	public static final double PATH_DRIFT_DISTANCE = 2.0;

	/**
	 * Ticks the bot may spend drifting off the route before the plan is abandoned.
	 *
	 * <p>Baritone's value is 200. That is far too patient here: Baritone catches a failing movement
	 * separately through its per-movement state machines, so the drift counter is a last resort, while
	 * for us it <em>is</em> the detector. At 200 the bot ground against a corner for ten seconds before
	 * trying anything else.</p>
	 */
	public static final int MAX_TICKS_OFF_PATH = 20;

	// ---------------------------------------------------------------- parkour

	/**
	 * How many nodes ahead a coming jump forces sprinting on. A sprint-jump lives or dies on the
	 * run-up: arriving at the launch block already at full speed is the difference between clearing a
	 * three-block gap and dropping into it. So as soon as a jump appears this close ahead, the
	 * approach sprints regardless of the usual "somewhere to sprint to" gate.
	 */
	public static final int PARKOUR_RUNWAY_STEPS = 4;

	/**
	 * Distance (blocks) at or above which a jump is taken at a full sprint; everything shorter is a
	 * <em>walking</em> jump.
	 *
	 * <p>The single most important number in parkour, and the one that was wrong. A sprint-jump
	 * carries roughly four blocks, so sprinting a two-block move (a one-block gap) sails clean over
	 * the landing and drops into the next hole — no landing brake can rescue a jump that never touched
	 * the block. Vanilla needs the sprint only for the three-block gap, which is distance 4 here, and
	 * that is exactly where Baritone draws the line: it prices distance 2 and 3 at the walking rate
	 * and only distance 4 at the sprinting rate.</p>
	 */
	public static final int PARKOUR_SPRINT_MIN_DISTANCE = 4;

	// ---------------------------------------------------------------- route tracking

	/**
	 * How many nodes ahead progress tracking will look when deciding how far along the route the
	 * player has got. Bounds the per-tick cost, and lets a fall or a shove that skipped several nodes
	 * be recognised instead of reading as being lost.
	 */
	public static final int MAX_LOOKAHEAD_STEPS = 8;

	/** Spacing between clearance samples along a candidate straight line. */
	public static final double LINE_SAMPLE_SPACING = 0.35;

	/**
	 * Half the player's collision width (0.6 wide), plus a margin. A straight-line walkability test
	 * checks this far to each side of the line, so it cannot approve a route that clips a corner the
	 * body would actually hit.
	 */
	public static final double PLAYER_HALF_WIDTH = 0.32;

	/**
	 * Grace period, in ticks, allowed to a movement <em>on top of what the planner estimated it would
	 * take</em>, before the bot decides it is stuck and replans.
	 *
	 * <p>Baritone budgets each movement against its own cost estimate rather than against one global
	 * timeout, and that distinction matters: a one-block walk and a tunnel through obsidian are not
	 * the same kind of slow. A flat timeout has to be set long enough for the worst legitimate case,
	 * which makes it far too patient with a bot wedged against a fence — the common failure. Adding
	 * the grace to the estimate keeps quick moves on a short leash while giving genuinely slow ones
	 * all the time they need.</p>
	 *
	 * <p>Progress is measured as movement <em>along the route</em>, not as movement in general. A bot
	 * circling on the spot or shuffling against a wall is moving the whole time, so a position-based
	 * test would never fire.</p>
	 */
	public static final int MOVEMENT_TIMEOUT_TICKS = 40;

	/** Consecutive failed searches before the bot gives up entirely. */
	public static final int MAX_REPLAN_FAILURES = 4;

	/**
	 * Ticks to wait for the player to land before planning anyway. A jump lands well inside this;
	 * the cap only matters for a genuine long fall, where planning snaps to the ground below.
	 */
	public static final int AIRBORNE_PLAN_WAIT = 15;

	/**
	 * How many times the bot may replan after getting stuck without ever getting meaningfully
	 * closer to the goal, before it concludes the goal is unreachable and stops.
	 */
	public static final int MAX_STUCK_REPLANS = 5;

	/**
	 * Reduction in the goal's own remaining-cost estimate that counts as "this replan actually
	 * helped". In ticks — roughly two blocks of sprinting.
	 */
	public static final double STUCK_PROGRESS_MARGIN = 7.0;

	/**
	 * Squared horizontal distance below which a steering target is treated as directly above or
	 * below us. Heading is meaningless at that range and the computed yaw is pure noise, which
	 * makes the player spin on the spot.
	 */
	public static final double YAW_DEADZONE_SQR = 0.04;

	/** How close (blocks) the bot must get to the goal to declare success. */
	public static final double GOAL_TOLERANCE = 0.8;

	/**
	 * Vertical slack on arrival. One block, not one and a half: at 1.5 the bot counted standing on
	 * top of the goal block as having arrived, which for ore hunting meant never actually mining it.
	 */
	public static final double GOAL_VERTICAL_TOLERANCE = 1.0;

	// ---------------------------------------------------------------- combat and healing

	/** Interaction reach. Vanilla survival is 4.5; stay under it for block break/place. */
	public static final double REACH = 4.0;

	/**
	 * Eye height of a standing player, for working out the view point from a <em>hypothetical</em>
	 * stance the bot is not occupying yet (reach and line-of-sight tests when picking where to
	 * stand). {@code player.getEyeHeight()} only answers for where the player actually is.
	 */
	public static final double STANDING_EYE_HEIGHT = 1.62;

	/**
	 * How far above the target block the player's feet must rise before a pillar block can be
	 * placed. A block cannot be placed into the space the player occupies, so the bot jumps and
	 * places underneath itself — this is the clearance that makes the space legal to fill.
	 */
	public static final double PILLAR_CLEARANCE = 1.0;

	/**
	 * A collision shape must reach at least this high within its block to count as standable
	 * ground. Full blocks (1.0) and dirt paths (0.9375) qualify; slabs (0.5) deliberately do not,
	 * because the block-grid model assumes the player's feet sit on a block boundary.
	 */
	public static final double MIN_GROUND_HEIGHT = 0.9;

	/**
	 * Lowest collision surface that still counts as something to stand on <em>inside</em> its own
	 * cell — see {@link mcbot.client.path.WorldView#isHalfSupport}.
	 *
	 * <p>Set just under a slab's 0.5 so bottom slabs qualify. Anything shallower (a carpet, a pressure
	 * plate, a lily pad) is walked over rather than stood on, and modelling it as a stance would only
	 * add nodes that behave identically to the ground beneath them.</p>
	 */
	public static final double MIN_HALF_GROUND_HEIGHT = 0.45;

	// ---------------------------------------------------------------- survival: combat and eating
	//
	// Not Baritone's territory — it has no combat or hunger handling at all. Kept as a deliberate
	// superset, because a navigation bot that starves or gets beaten to death mid-route does not
	// finish the route.

	/**
	 * Eat when hunger is at or below this. Sprinting needs more than 6 hunger points, so topping up
	 * well before that keeps the bot at sprint speed rather than trudging.
	 */
	public static final int EAT_BELOW_FOOD_LEVEL = 14;

	/** Below this health an emergency food (golden apple) is worth spending. */
	public static final float EMERGENCY_HEAL_HEALTH = 6.0f;

	/** Missing health that justifies eating purely to enable regeneration. */
	public static final float HEAL_BY_EATING_THRESHOLD = 4.0f;

	/** Natural regeneration only runs at or above this hunger level. */
	public static final int REGEN_FOOD_LEVEL = 18;

	/** Attack-strength fraction to wait for. Swinging early does a fraction of the damage. */
	public static final float ATTACK_STRENGTH_THRESHOLD = 0.9f;

	/**
	 * Distance at which a hostile is dealt with. Deliberately close to vanilla's 3-block attack
	 * reach: the bot defends itself from what reaches it, rather than chasing everything it sees
	 * and never finishing the journey.
	 */
	public static final double COMBAT_ENGAGE_RANGE = 3.5;

	/** Keep this far from a swelling creeper. Trading hits with one is never worth it. */
	public static final double CREEPER_DANGER_RANGE = 6.0;
}

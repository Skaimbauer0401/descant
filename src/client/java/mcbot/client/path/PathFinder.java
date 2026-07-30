package mcbot.client.path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import mcbot.client.BotSettings;
import mcbot.client.path.goal.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Time-sliced A* search over the block grid.
 *
 * <p>The search runs on the client thread — world access from any other thread is unsafe — but is
 * spread across ticks via {@link #advance(long)}, which does a bounded amount of work and returns.
 * A search that would take 200 ms therefore costs ~3 ms on each of 60 ticks instead of freezing the
 * game once, and the world state it reads is always consistent.</p>
 *
 * <p>When the node budget runs out before the goal is found, the search still returns the route to
 * the node that got closest ({@link Path#reachesGoal()} is then {@code false}). Walking that
 * partial route and replanning on arrival is what lets the bot travel arbitrarily far and into
 * chunks that were not yet loaded when planning began.</p>
 */
public final class PathFinder {

	/** Horizontal moves: four cardinals first (cheapest, most common), then four diagonals. */
	private static final int[][] HORIZONTAL = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private static final List<BlockPos> NO_BREAK = List.of();

	/** How many ancestors back to look when asking what this route has already built. */
	private static final int EDIT_LOOKBACK = 12;

	/**
	 * Blends used to pick a fallback route when the goal was not reached, from most cost-aware to most
	 * greedy. For each one the search remembers the node minimising {@code h + g / coefficient}.
	 *
	 * <p>Baritone's trick, and a genuinely important one. Falling back to "whichever node ended up
	 * closest to the goal" — the obvious choice, and what this used to do — ignores what that node
	 * <em>cost</em> to reach, so it will happily hand back a route that descends fifty blocks into a
	 * ravine because the bottom of it is two blocks nearer the target. Dividing the accrued cost by a
	 * coefficient re-admits it into the comparison: a low coefficient weighs cost heavily and picks a
	 * sober, well-earned route, a high one tolerates an expensive detour for real progress. The search
	 * takes the most cost-aware answer that got anywhere at all, and only slides towards the greedy
	 * ones when the careful ones did not.</p>
	 */
	private static final double[] FALLBACK_COEFFICIENTS = { 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0 };

	/** A fallback route must lead at least this far from the start to be worth walking at all. */
	private static final double MIN_FALLBACK_DISTANCE = 5.0;

	/** Improvement below which a new best-so-far is not worth recording (avoids pointless churn). */
	private static final double MIN_IMPROVEMENT = 0.01;

	public enum State {
		/** Still working; call {@link #advance(long)} again next tick. */
		SEARCHING,
		/** The goal was reached. */
		SUCCESS,
		/** Budget exhausted; {@link #result()} leads as close to the goal as we could get. */
		PARTIAL,
		/** No route at all — the start is walled in, or nothing improved on it. */
		FAILED
	}

	private final WorldView world;
	private final Goal goal;
	private final List<ItemStack> tools;
	private final boolean allowBreak;
	private final boolean allowPlace;
	private final boolean allowParkour;

	/**
	 * Whether the bot can sprint, which in vanilla means hunger above 6.
	 *
	 * <p>Snapshotted at construction rather than read live, like {@link #tools}: the search is spread
	 * across ticks, and a route half planned as a sprinter and half as a walker would be a route that
	 * is valid nowhere.</p>
	 */
	private final boolean canSprint;

	private final Map<Long, Node> nodes = new HashMap<>();
	private final PriorityQueue<Entry> open = new PriorityQueue<>();

	/**
	 * The world edits the route has already committed to on the way to the node currently being
	 * expanded: {@code TRUE} = a block placed there (now a full solid cube), {@code FALSE} = a block
	 * broken there (now air). Rebuilt once per expansion from the node's ancestry and consulted by
	 * every terrain query, so the search reasons about the world <em>as it will be after its own
	 * digging and building</em>, not merely as it is live. Reused across expansions to avoid churn.
	 */
	private final Map<Long, Boolean> simEdits = new HashMap<>();

	/**
	 * Positions the previous route passed through, whose cost is discounted so a replan tends to
	 * re-follow the road it was already on. Empty when there was no previous route.
	 */
	private final Set<Long> favoured;

	private final Node startNode;
	private Node goalNode;

	/** Best node found under each {@link #FALLBACK_COEFFICIENTS} blend, and that blend's value. */
	private final Node[] bestNode = new Node[FALLBACK_COEFFICIENTS.length];
	private final double[] bestBlend = new double[FALLBACK_COEFFICIENTS.length];

	private int expanded;
	private Path result;
	private State state = State.SEARCHING;

	/**
	 * @param tools     snapshot of the hotbar, used to estimate mining times without touching the live
	 *                  inventory from inside the search
	 * @param canSprint whether the bot can sprint right now; when it cannot, the jumps that need one
	 *                  are not generated at all
	 * @param favoured  positions on the route being replaced, whose cost is discounted; pass an empty
	 *                  set for a fresh journey
	 */
	public PathFinder(WorldView world, BlockPos start, Goal goal, List<ItemStack> tools,
			boolean allowBreak, boolean allowPlace, boolean allowParkour, boolean canSprint,
			Set<Long> favoured) {
		this.world = world;
		this.goal = goal;
		this.tools = tools;
		this.allowBreak = allowBreak;
		this.allowPlace = allowPlace;
		this.allowParkour = allowParkour;
		this.canSprint = canSprint;
		this.favoured = favoured;

		Arrays.fill(bestBlend, Double.POSITIVE_INFINITY);

		this.startNode = new Node(start.immutable());
		this.startNode.g = 0.0;
		this.startNode.h = heuristic(start);
		this.startNode.f = this.startNode.h;
		this.nodes.put(start.asLong(), this.startNode);
		this.open.add(new Entry(start.asLong(), this.startNode.f));
	}

	public State state() {
		return state;
	}

	public Path result() {
		return result;
	}

	public int expandedNodes() {
		return expanded;
	}

	// ---------------------------------------------------------------- search loop

	/**
	 * Runs the search for at most {@code budgetNanos} nanoseconds.
	 *
	 * @return the current state; anything other than {@link State#SEARCHING} means it is done
	 */
	public State advance(long budgetNanos) {
		if (state != State.SEARCHING) {
			return state;
		}
		long deadline = System.nanoTime() + budgetNanos;
		int sinceTimeCheck = 0;

		while (!open.isEmpty()) {
			if (expanded >= BotSettings.MAX_NODES.get()) {
				return finish(false);
			}
			// Checking the clock is not free, so only do it every so often.
			if (++sinceTimeCheck >= 64) {
				sinceTimeCheck = 0;
				if (System.nanoTime() >= deadline) {
					return State.SEARCHING;
				}
			}

			Entry entry = open.poll();
			Node node = nodes.get(entry.key());
			// Stale queue entry: this node was later reached more cheaply and re-queued.
			if (node == null || node.closed || entry.f() > node.f + 1.0e-9) {
				continue;
			}
			node.closed = true;
			expanded++;

			if (goal.isInGoal(node.pos)) {
				goalNode = node;
				return finish(true);
			}
			expand(node);
		}
		return finish(false);
	}

	private State finish(boolean reachedGoal) {
		Node end = reachedGoal ? goalNode : bestFallback();
		if (end == null || end == startNode) {
			// Never got anywhere worth walking to — genuinely stuck.
			state = State.FAILED;
			return state;
		}

		Deque<Path.Step> steps = new ArrayDeque<>();
		// Each step records the node it came from as well as the node it reaches. The executor drives
		// movements, not waypoints, and a movement without a start has no direction — which is what a
		// jump needs to know where its launch block and its run-up are. It also carries what this one
		// move was estimated to cost, which is the executor's per-movement timeout budget.
		for (Node n = end; n != null && n != startNode; n = n.parent) {
			steps.addFirst(new Path.Step(n.parent.pos, n.pos, n.toBreak, n.toPlace, n.parkourDistance,
					n.g - n.parent.g));
		}
		result = new Path(new ArrayList<>(steps), goal, reachedGoal);
		state = reachedGoal ? State.SUCCESS : State.PARTIAL;
		return state;
	}

	/**
	 * Records a node against every fallback blend it improves on. Called for each node the search
	 * reaches, so a route that is abandoned part-way still leaves behind the best partial answers.
	 */
	private void trackFallback(Node node) {
		for (int index = 0; index < FALLBACK_COEFFICIENTS.length; index++) {
			double blend = node.h + node.g / FALLBACK_COEFFICIENTS[index];
			if (blend < bestBlend[index] - MIN_IMPROVEMENT) {
				bestBlend[index] = blend;
				bestNode[index] = node;
			}
		}
	}

	/**
	 * The route to hand back when the goal was not reached: the most cost-aware fallback that actually
	 * led somewhere.
	 *
	 * <p>The distance floor is what stops the bot shuffling. A fallback one or two blocks from where it
	 * already stands is not progress — it is a plan to take a step, arrive, replan, and take another,
	 * which reads in-world as a bot twitching against an obstacle rather than routing round it.</p>
	 */
	private Node bestFallback() {
		double minimum = MIN_FALLBACK_DISTANCE * MIN_FALLBACK_DISTANCE;
		for (Node candidate : bestNode) {
			if (candidate != null && candidate != startNode
					&& candidate.pos.distSqr(startNode.pos) > minimum) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Estimated remaining cost. Uses the sprint cost per horizontal block — cheaper than any move the
	 * bot can actually make — so the estimate never exceeds the true remaining cost and A* stays
	 * optimal.
	 *
	 * <p>Route favouring is the one deliberate exception: discounting the incumbent route pushes some
	 * real costs below this estimate, which costs strict optimality. That is the trade Baritone makes
	 * too, and it is the right one — a theoretically optimal route that flips to a different equally
	 * optimal one every replan is worse to watch and worse to walk than a marginally longer route the
	 * bot commits to.</p>
	 */
	private double heuristic(BlockPos pos) {
		return goal.heuristic(pos);
	}

	// ---------------------------------------------------------------- move generation

	private void expand(Node node) {
		buildSimEdits(node);
		for (int[] dir : HORIZONTAL) {
			tryHorizontal(node, dir[0], dir[1]);
		}
		trySwimVertical(node);
		tryClimb(node);
		tryPillarUp(node);
		tryMineDown(node);
		tryParkour(node);
	}

	/**
	 * Going up and down a ladder or a vine.
	 *
	 * <p>Baritone has no dedicated climbing move — it folds climbing into the same {@code (0, ±1, 0)}
	 * moves as pillaring and digging down, because from the search's point of view a ladder is simply a
	 * far cheaper way to buy the same vertical block. This does the same, and it matters more than it
	 * sounds: without it, a bot standing at the foot of a ladder could only reach the top by pillaring
	 * up beside it at {@link BotSettings#PLACE_COST} a block, or report the top unreachable.</p>
	 */
	private void tryClimb(Node node) {
		BlockPos from = node.pos;
		if (!world.isKnown(from)) {
			return;
		}

		// Going up needs us already on the ladder — you cannot climb one you are standing beside.
		// Keep climbing only while there is more ladder above: stepping off the top is an ordinary
		// walk out, which the horizontal moves already generate.
		BlockPos up = from.above();
		if (world.isClimbable(from)
				&& world.isKnown(up) && world.isClimbable(up) && simPassable(up.above())) {
			add(node, up, BotSettings.LADDER_UP_COST.get(), NO_BREAK, null);
		}

		// Going down only needs a ladder *below* us, so this also covers stepping off solid ground at
		// the top of a shaft and taking hold of the ladder on the way past. Without that case the bot
		// can climb a ladder but never descend one it did not already start on — it would plan a free
		// fall down its own shaft instead.
		BlockPos down = from.below();
		if (world.isKnown(down) && world.isClimbable(down) && simFitsAt(down)) {
			add(node, down, BotSettings.LADDER_DOWN_COST.get(), NO_BREAK, null);
		}
	}

	/**
	 * Rebuilds {@link #simEdits} for the node about to be expanded: walks its recent ancestry and
	 * records each break and place, nearest edit winning. Bounded to {@link #EDIT_LOOKBACK} ancestors
	 * — an edit only ever affects moves within a handful of steps of it, so a shallow walk captures
	 * the whole benefit cheaply, and the branching search rebuilds this fresh for every node so one
	 * branch's digging never leaks into another's.
	 */
	private void buildSimEdits(Node node) {
		simEdits.clear();
		Node current = node;
		for (int depth = 0; current != null && depth < EDIT_LOOKBACK; depth++) {
			if (current.toPlace != null) {
				simEdits.putIfAbsent(current.toPlace.asLong(), Boolean.TRUE);
			}
			for (BlockPos broken : current.toBreak) {
				simEdits.putIfAbsent(broken.asLong(), Boolean.FALSE);
			}
			current = current.parent;
		}
	}

	// ---------------------------------------------------------------- simulated-world queries
	//
	// Every terrain question in move generation goes through these rather than straight to WorldView,
	// so a block the route plans to break reads as air, and one it plans to place reads as solid,
	// for all the moves that follow. With no planned edits nearby they fall straight through to the
	// live world, so ordinary ground is unaffected.

	/** {@code TRUE} placed here, {@code FALSE} broken here, {@code null} untouched by the route. */
	private Boolean planned(BlockPos pos) {
		return simEdits.get(pos.asLong());
	}

	/** The player's body may occupy this cell — air (incl. blocks we broke), not a solid we placed. */
	private boolean simPassable(BlockPos pos) {
		Boolean edit = planned(pos);
		return edit != null ? !edit : world.isPassable(pos);
	}

	/** Something to stand on here — a placed block, or live standable ground we did not dig out. */
	private boolean simStandable(BlockPos pos) {
		Boolean edit = planned(pos);
		return edit != null ? edit : world.isStandable(pos);
	}

	/** A two-tall body fits with feet here, honouring planned edits at both feet and head. */
	private boolean simFitsAt(BlockPos feet) {
		return simPassable(feet) && simPassable(feet.above());
	}

	/**
	 * A half-height block filling this cell's floor that the player stands on top of, inside the cell.
	 * A cell the route has edited is a full cube or empty air, so it is never a half support.
	 */
	private boolean simHalfSupport(BlockPos pos) {
		return planned(pos) == null && world.isHalfSupport(pos);
	}

	/**
	 * Whether the player can stand on a half block filling this cell. Standing half a block higher
	 * pushes the head into the cell two above rather than stopping at the top of the one above, so
	 * this needs a taller clearance than ordinary ground does.
	 */
	private boolean simStandsOnHalf(BlockPos feet) {
		return simHalfSupport(feet) && simPassable(feet.above()) && simPassable(feet.above(2));
	}

	/** Water here. A cell we placed into or dug out is solid or air respectively — never water. */
	private boolean simWater(BlockPos pos) {
		return planned(pos) == null && world.isWater(pos);
	}

	/** Empty enough to place a block here — air (incl. our own digging), never a cube we placed. */
	private boolean simFillable(BlockPos pos) {
		Boolean edit = planned(pos);
		return edit != null ? !edit : world.isFillable(pos);
	}

	/**
	 * A floor whose surface sits flush with the top of its block — a full cube, not a slab, a stair or
	 * a snow layer. Jumps launch and land on these only, because a partial block puts the feet off the
	 * grid the whole arc is measured against.
	 *
	 * <p>A block the route plans to place counts automatically: placement always produces a full cube,
	 * and the live world (which still shows air there) cannot answer for it.</p>
	 */
	private boolean simFlushFloor(BlockPos pos) {
		Boolean edit = planned(pos);
		if (edit != null) {
			return edit;
		}
		return world.isStandable(pos) && world.surfaceHeight(pos) >= 1.0;
	}

	/**
	 * Running jumps across a gap.
	 *
	 * <p>A jump clears a gap the bot would otherwise have to bridge across (slow, and eats blocks) or
	 * detour around. Only straight cardinal jumps from solid ground are generated — diagonal and water
	 * launches are far less reliable to land — and only when the neighbour is genuinely a gap, so a
	 * jump is never planned where an ordinary step would do.</p>
	 */
	private void tryParkour(Node node) {
		if (!allowParkour) {
			return;
		}
		BlockPos from = node.pos;
		// The launch block must be a flush, full-height cube. Baritone refuses to jump off slabs,
		// stairs, ladders and soul sand for the same reason: the take-off height and the friction are
		// not what the arc assumes, so where the player lands stops being predictable.
		if (!simFlushFloor(from.below())) {
			return;
		}
		if (!world.isKnown(from.above(2)) || !simPassable(from.above(2))) {
			return; // no room above to jump into
		}
		// Cardinals only: the first four HORIZONTAL entries.
		for (int i = 0; i < 4; i++) {
			tryParkourJump(node, from, HORIZONTAL[i][0], HORIZONTAL[i][1]);
		}
	}

	private void tryParkourJump(Node node, BlockPos from, int dx, int dz) {
		BlockPos first = from.offset(dx, 0, dz);
		// The immediate neighbour must be an open gap: body-space clear, nothing to stand on. If it
		// were standable, an ordinary walk or step already covers it and costs less.
		if (!world.isKnown(first) || !simFitsAt(first) || simStandable(first.below())) {
			return;
		}

		int furthest = BotSettings.MAX_PARKOUR_DISTANCE.get();
		if (!canSprint) {
			// Hunger at or below 6 and vanilla simply refuses to sprint. A jump priced and flown as a
			// sprint then leaves at walking speed and lands in the gap, which is not a slower route but
			// a fall — so the long ones are not generated at all rather than generated and hoped for.
			furthest = Math.min(furthest, BotSettings.PARKOUR_SPRINT_MIN_DISTANCE.get() - 1);
		}

		for (int distance = 2; distance <= furthest; distance++) {
			BlockPos column = from.offset(dx * distance, 0, dz * distance);
			if (!world.isKnown(column)) {
				return; // cannot see the landing; do not guess across unloaded chunks
			}
			// Land at the same level.
			if (simFitsAt(column) && simStandable(column.below())) {
				addParkour(node, column, distance, dx, dz, false);
				return;
			}
			// Land one block *up*. Part of the arc is spent climbing, so this reaches less far and is
			// only worth generating for the shorter jumps — but without it every raised ledge across a
			// gap has to be bridged or walked around.
			BlockPos higher = column.above();
			// Every ascending jump is flown at a sprint whatever its length — part of the arc is spent
			// climbing, so it needs the speed to still reach — which means none of them at all when
			// there is no sprint to be had.
			if (canSprint && distance <= BotSettings.MAX_PARKOUR_ASCEND_DISTANCE.get()
					&& world.isKnown(higher) && simFitsAt(higher) && simFlushFloor(column)
					&& ascendCorridorClear(from, dx, dz, distance)) {
				addParkour(node, higher, distance, dx, dz, true);
				return;
			}
			// Land one block down — a jump naturally arcs and drops.
			BlockPos lower = column.below();
			if (world.isKnown(lower) && simFitsAt(lower) && simStandable(lower.below())) {
				addParkour(node, lower, distance, dx, dz, false);
				return;
			}
			// No landing here. Keep going only while the flight corridor stays clear; a wall ends it.
			if (!simFitsAt(column)) {
				return;
			}
		}
	}

	private void addParkour(Node parent, BlockPos landing, int distance, int dx, int dz,
			boolean ascend) {
		if (!overshootSafe(landing, dx, dz)) {
			return;
		}
		// Priced the way it will actually be flown. Distance 2 and 3 are *walking* jumps in vanilla and
		// are charged at the walking rate; only distance 4 — the three-block gap — needs the sprint.
		// Pricing every jump as a sprint, as this used to, told the executor to sprint them all, and a
		// sprint-jump across a one-block gap overshoots the landing entirely.
		double cost = distance >= BotSettings.PARKOUR_SPRINT_MIN_DISTANCE.get()
				? distance * BotSettings.SPRINT_COST.get()
				: distance * BotSettings.WALK_COST.get();
		cost += BotSettings.JUMP_PENALTY.get();
		if (ascend) {
			cost += BotSettings.JUMP_COST.get();
		}

		// Bridging is preferred to jumping wherever both are possible, so a jump is surcharged past
		// what bridging the same span would cost. Only when the bot could actually build, though: with
		// nothing to place, a jump is the cheap option again rather than a reason to walk miles around.
		if (allowPlace) {
			cost += (distance - 1) * BotSettings.PARKOUR_BRIDGE_SURCHARGE.get();
		}
		add(parent, landing, cost, NO_BREAK, null, distance);
	}

	/**
	 * Whether the flight corridor to an <em>ascending</em> landing is clear overhead.
	 *
	 * <p>A jump that gains a block rises through a corridor a block taller than the one an ordinary
	 * two-block body walks in, so the head passes above the cells the flat checks look at. Without
	 * this the planner will happily route a rising jump into a ceiling it never examined, and the bot
	 * clips it and drops into the gap.</p>
	 */
	private boolean ascendCorridorClear(BlockPos from, int dx, int dz, int distance) {
		for (int step = 1; step < distance; step++) {
			BlockPos overhead = from.offset(dx * step, 0, dz * step).above(2);
			if (!world.isKnown(overhead) || !simPassable(overhead)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether landing here is safe even if momentum carries the player a little past it.
	 *
	 * <p>A jump does not stop dead on the landing block, so a route that lands one block short of lava
	 * or a cactus is a route that lands in it. Baritone makes the same check before accepting a jump;
	 * doing it at plan time is what removes the need for the executor to brake on landing at all.</p>
	 */
	private boolean overshootSafe(BlockPos landing, int dx, int dz) {
		BlockPos beyond = landing.offset(dx, 0, dz);
		if (!world.isKnown(beyond)) {
			return true; // unseen, and we only skid into it at worst — do not reject the whole jump
		}
		return !world.isHazard(beyond) && !world.isHazard(beyond.above());
	}

	/**
	 * Swimming straight up or down.
	 *
	 * <p>Without this there is no vertical move available in water at all: pillaring needs footing
	 * and mining down needs a breakable block, so a submerged bot could only ever travel sideways
	 * and any route to the surface was reported unreachable.</p>
	 */
	private void trySwimVertical(Node node) {
		BlockPos from = node.pos;
		if (!simWater(from)) {
			return;
		}

		BlockPos up = from.above();
		// Buoyancy only lifts us into more water. A puddle or a flowing half-block still counts as
		// "water" by the fluid tag, but there is nothing above to rise through — planning a swim up
		// out of one produces a move the physics will never perform, and the bot wedges there
		// retrying until it gives up. Climbing out of water is a step-up or a jump, not a swim.
		if (world.isKnown(up) && simWater(up)) {
			if (simFitsAt(up)) {
				add(node, up, BotSettings.SWIM_COST.get(), NO_BREAK, null);
			} else if (simPassable(up)) {
				// Body space is clear but the ceiling above it is not. Mine straight up while
				// treading water — no footing needed, which is what makes this possible here and
				// not on dry land.
				BlockPos ceiling = from.above(2);
				int ticks = breakTicks(ceiling);
				if (ticks >= 0) {
					add(node, up, BotSettings.SWIM_COST.get() + ticks + BotSettings.BREAK_OVERHEAD.get(),
							List.of(ceiling), null);
				}
			}
		}

		BlockPos down = from.below();
		if (world.isKnown(down) && simFitsAt(down) && simWater(down)) {
			add(node, down, BotSettings.SWIM_COST.get(), NO_BREAK, null);
		}
	}

	private void tryHorizontal(Node node, int dx, int dz) {
		BlockPos from = node.pos;
		BlockPos target = from.offset(dx, 0, dz);
		if (!world.isKnown(target)) {
			return; // never plan into chunks we cannot see
		}

		boolean diagonal = dx != 0 && dz != 0;
		double distance = diagonal ? BotSettings.DIAGONAL_MULTIPLIER : 1.0;
		double baseCost = BotSettings.WALK_COST.get() * distance;

		if (diagonal) {
			// Both orthogonal components must be body-clear, or the move cuts the corner of a block.
			//
			// Baritone only requires one, and permits the resulting squeeze along the wall face. That
			// is legal in vanilla but it is not free: the player scrapes the corner, loses speed, and
			// on a smoothed route the steering is already leaning into the turn when it happens. In
			// practice it produced routes that wedged on corners, so this stays stricter than Baritone
			// deliberately. A staircase of cardinal moves round the corner is slower on paper and
			// considerably faster in the world.
			if (!simFitsAt(from.offset(dx, 0, 0)) || !simFitsAt(from.offset(0, 0, dz))) {
				return;
			}
		}

		if (simFitsAt(target)) {
			// Head underwater as well as feet: this is a swim, and must be priced as one even though a
			// sea floor may sit one block below. Treating that floor as a cheap walk was why the bot
			// dived to the bottom and trudged across it — WALK_COST per block undercut SWIM_COST — the
			// long way round when it could have swum straight over the top. Wading a shallow stream
			// (feet in water, head in air) is left as a walk, which is what it physically is.
			boolean submerged = simWater(target) && simWater(target.above());
			if (submerged) {
				add(node, target, BotSettings.SWIM_COST.get() * distance, NO_BREAK, null);
			} else if (world.isClimbable(target)) {
				// Stepping onto a ladder or vine. It holds the player up without anything underfoot, so
				// this has to be caught before the "nothing to stand on" branch below, which would
				// otherwise plan a fall down the ladder shaft or bridge across its face.
				add(node, target, baseCost, NO_BREAK, null);
			} else if (simStandable(target.below())) {
				// Same block level does not mean same standing height: a slab or a layer of snow
				// inside the destination raises the floor, and enough of it turns a level walk into
				// a climb the player cannot make.
				addWithClimb(node, from, target, baseCost);
			} else if (simWater(target)) {
				add(node, target, BotSettings.SWIM_COST.get() * distance, NO_BREAK, null);
			} else {
				tryDescend(node, target, baseCost);
				// Nothing to stand on: bridge across by placing a block underfoot. The anchor may
				// be a block this same route places a step earlier, which is what lets a bridge run
				// further than one block out over a gap.
				if (allowPlace && canBuildSupport(target.below())) {
					add(node, target, baseCost + BotSettings.PLACE_COST.get(), NO_BREAK, target.below());
				}
			}
		} else if (simStandsOnHalf(target)) {
			// Stepping onto a bottom slab. The destination node is the slab's own cell, because that is
			// where the feet coordinate lands; half a block is inside the player's automatic step
			// height, so this costs a plain walk with no jump.
			addWithClimb(node, from, target, baseCost);
		} else if (simStandable(target) && simFitsAt(target.above())) {
			// A one-block step up. Jumping needs clearance above our own head, which may itself
			// have to be mined out — otherwise a low ceiling blocks every climb.
			BlockPos headroom = from.above(2);
			BlockPos landing = target.above();
			if (world.climbHeight(from, landing) > BotSettings.MAX_JUMP_HEIGHT) {
				return; // higher than a jump reaches — usually a partial block adding to the step
			}
			if (simPassable(headroom)) {
				add(node, landing, baseCost + BotSettings.JUMP_COST.get(), NO_BREAK, null);
			} else {
				int ticks = breakTicks(headroom);
				if (ticks >= 0) {
					add(node, landing,
							baseCost + BotSettings.JUMP_COST.get() + ticks + BotSettings.BREAK_OVERHEAD.get(),
							List.of(headroom), null);
				}
			}
		} else if (allowBreak && !diagonal) {
			// Diagonal mining is skipped deliberately: it multiplies the number of blocks to clear
			// for very little gain, and the resulting corners are awkward to walk.
			tryBreakThrough(node, target, baseCost);
			tryBreakUp(node, target, baseCost);
		}
	}

	/**
	 * Cutting a step upwards through solid ground — one stair of a staircase.
	 *
	 * <p>The move that was missing, and its absence made the surface unreachable from underground.
	 * Buried in stone, every branch above this one is unavailable: there is nowhere to walk to, nothing
	 * to step onto, and no gap to bridge. That left {@link #tryPillarUp} as the <em>only</em> way to
	 * gain a block, and pillaring needs {@code allowPlace} and a stack of blocks to spend. Without
	 * either — walking mode, or simply out of cobble — A* had no upward move at all, so a goal above
	 * the bot could not be improved on by any expansion, {@code bestFallback} found nothing better than
	 * the start, and the search reported no route. Which was true of the moves it knew, and obviously
	 * false to anyone watching: you dig upwards by digging upwards.</p>
	 *
	 * <p>Three blocks come out — the headroom to jump from, and the feet and head of the new stance —
	 * against the one a pillar breaks. It is dearer per block and it spends nothing, which is the
	 * trade A* should be the one to weigh.</p>
	 */
	private void tryBreakUp(Node node, BlockPos target, double baseCost) {
		BlockPos from = node.pos;
		// The tread has to already be there. This cuts a stair out of ground; it does not build one,
		// which is what pillaring is for.
		if (!simStandable(target)) {
			return;
		}
		// Measured to the top of the tread, not to the landing cell — the landing is still solid at
		// this point and would price the step at two blocks and be rejected for it.
		if (world.climbHeight(from, target) > BotSettings.MAX_JUMP_HEIGHT) {
			return;
		}

		BlockPos landing = target.above();
		List<BlockPos> toBreak = new ArrayList<>(3);
		double cost = baseCost + BotSettings.JUMP_COST.get() + BotSettings.BREAK_OVERHEAD.get();

		// Headroom first: it is what the jump needs, and breaking it before leaving the ground is also
		// the order the mover will carry out.
		for (BlockPos pos : List.of(from.above(2), landing, landing.above())) {
			if (simPassable(pos)) {
				continue;
			}
			int ticks = breakTicks(pos);
			if (ticks < 0) {
				return;
			}
			cost += ticks;
			toBreak.add(pos);
		}

		if (toBreak.isEmpty() || toBreak.size() > BotSettings.MAX_BREAK_PER_MOVE.get()) {
			return;
		}
		add(node, landing, cost, toBreak, null);
	}

	/**
	 * Adds a same-level move, pricing in any climb the destination's floor actually demands.
	 *
	 * <p>Rejects the move outright when the real surfaces are further apart than a jump reaches.
	 * Comparing block coordinates alone would call it flat and the bot would walk into the step
	 * forever.</p>
	 */
	private void addWithClimb(Node node, BlockPos from, BlockPos target, double baseCost) {
		double climb = world.climbHeight(from, target);
		if (climb > BotSettings.MAX_JUMP_HEIGHT) {
			return;
		}
		double cost = baseCost + (climb > BotSettings.STEP_HEIGHT ? BotSettings.JUMP_COST.get() : 0.0);
		add(node, target, cost, NO_BREAK, null);
	}

	/** Walking off a ledge: find where we would land, and refuse drops that would hurt. */
	private void tryDescend(Node node, BlockPos target, double baseCost) {
		for (int drop = 1; drop <= BotSettings.MAX_FALL_SCAN.get(); drop++) {
			BlockPos feet = target.below(drop);
			if (!world.isKnown(feet) || !simPassable(feet)) {
				return;
			}
			if (simWater(feet)) {
				// Water cancels fall damage regardless of height.
				add(node, feet, baseCost + drop * BotSettings.FALL_COST_PER_BLOCK.get(), NO_BREAK, null);
				return;
			}
			if (simHalfSupport(feet)) {
				// Landed on a slab. Its cell is the stance, and the drop is half a block shorter than
				// the cell count suggests — close enough not to matter for fall damage.
				add(node, feet, baseCost + drop * BotSettings.FALL_COST_PER_BLOCK.get(), NO_BREAK, null);
				return;
			}
			if (simStandable(feet.below())) {
				double fallCost = baseCost + drop * BotSettings.FALL_COST_PER_BLOCK.get();
				if (drop <= BotSettings.SAFE_FALL_DISTANCE) {
					add(node, feet, fallCost, NO_BREAK, null);
				}
				// Anything deeper hurts, and nothing in the bot's repertoire softens a landing, so the
				// move is simply not generated — the search routes around the drop instead.
				return; // ground found either way; deeper scanning is pointless
			}
		}
	}

	/** Mining straight through an obstruction at head and/or foot height. */
	private void tryBreakThrough(Node node, BlockPos target, double baseCost) {
		if (!simStandable(target.below())) {
			return; // clearing the wall would leave us with nothing to walk on
		}
		List<BlockPos> toBreak = new ArrayList<>(BotSettings.MAX_BREAK_PER_MOVE.get());
		double cost = baseCost + BotSettings.BREAK_OVERHEAD.get();

		for (BlockPos pos : List.of(target, target.above())) {
			if (simPassable(pos)) {
				continue;
			}
			int ticks = breakTicks(pos);
			if (ticks < 0) {
				return;
			}
			cost += ticks;
			toBreak.add(pos);
		}

		if (toBreak.isEmpty() || toBreak.size() > BotSettings.MAX_BREAK_PER_MOVE.get()) {
			return;
		}
		add(node, target, cost, toBreak, null);
	}

	/**
	 * Jump and place a block underfoot to gain height, mining the ceiling first if there is one.
	 *
	 * <p>Without the mining case a bot underground can never go up: every pillar move would be
	 * rejected for lack of headroom, and A* would report the surface as unreachable.</p>
	 */
	private void tryPillarUp(Node node) {
		if (!allowPlace) {
			return;
		}
		BlockPos from = node.pos;
		BlockPos headroom = from.above(2);
		if (!world.isKnown(headroom)) {
			return;
		}
		if (!hasFooting(from)) {
			return; // pillaring requires solid footing to jump from
		}

		double cost = BotSettings.PLACE_COST.get() + BotSettings.JUMP_COST.get();
		List<BlockPos> toBreak = NO_BREAK;

		if (!simPassable(headroom)) {
			int ticks = breakTicks(headroom);
			if (ticks < 0) {
				return;
			}
			cost += ticks + BotSettings.BREAK_OVERHEAD.get();
			toBreak = List.of(headroom);
		}
		add(node, from.above(), cost, toBreak, from);
	}

	/**
	 * Whether the player has something to stand on at {@code feet} — counting blocks this route
	 * has not placed yet.
	 *
	 * <p>This is what lets pillars chain. The search reads the world as it is <em>now</em>, so the
	 * support for the second pillar block is missing: it is the block the first move is about to
	 * place. Judging purely on the live world therefore rejects every pillar move after the first,
	 * and A* returns a one-block path. Crediting the parent move's placement fixes the whole
	 * column in a single plan.</p>
	 */
	private boolean hasFooting(BlockPos feet) {
		// simStandable already counts a block the route has placed below as solid footing — the very
		// thing that lets pillars chain, since the search reads the live world where that block does
		// not exist yet. Treading water, and hanging off a ladder, work as launch platforms just as
		// well as ground does.
		return simStandable(feet.below()) || simWater(feet) || world.isClimbable(feet)
				|| simHalfSupport(feet);
	}

	/**
	 * Whether a block placed at {@code pos} would have something to be clicked against — counting
	 * blocks this route places on the way (which {@link #planned} reports as solid cubes).
	 */
	private boolean hasPlacementAnchor(BlockPos pos) {
		if (world.findPlacementFace(pos) != null) {
			return true;
		}
		for (Direction direction : Direction.values()) {
			if (planned(pos.relative(direction)) == Boolean.TRUE) {
				return true;
			}
		}
		return false;
	}

	/** Whether the route can fill {@code pos} to stand on, building off its own blocks if needed. */
	private boolean canBuildSupport(BlockPos pos) {
		return world.isKnown(pos) && simFillable(pos) && hasPlacementAnchor(pos);
	}

	/**
	 * Mining time for a block we are willing to break, or {@code -1} if we will not or cannot.
	 * Folds the permission check, the breakability check and the estimate into one call.
	 */
	private int breakTicks(BlockPos pos) {
		// A cell the route already edited is not there to be broken again: one it dug out is air, and
		// one it placed is its own scaffolding. Either way there is no fresh mining to price here.
		if (!allowBreak || planned(pos) != null || !world.isBreakable(pos)) {
			return -1;
		}
		return world.estimateBreakTicks(pos, bestTool(world.state(pos)));
	}

	/** Mine the block underfoot to descend. */
	private void tryMineDown(Node node) {
		if (!allowBreak) {
			return;
		}
		BlockPos below = node.pos.below();
		if (!world.isKnown(below)) {
			return;
		}
		if (!simStandable(below.below())) {
			return; // would drop us into an unknown hole
		}
		int ticks = breakTicks(below);
		if (ticks < 0) {
			return;
		}
		add(node, below, ticks + BotSettings.BREAK_OVERHEAD.get(), List.of(below), null);
	}

	// ---------------------------------------------------------------- bookkeeping

	private void add(Node parent, BlockPos pos, double cost, List<BlockPos> toBreak, BlockPos toPlace) {
		add(parent, pos, cost, toBreak, toPlace, 0);
	}

	private void add(Node parent, BlockPos pos, double cost, List<BlockPos> toBreak, BlockPos toPlace,
			int parkourDistance) {
		long key = pos.asLong();

		// Discount moves back onto the route we were already walking. Two routes round an obstacle are
		// very often within a rounding error of each other, and without a thumb on the scale each
		// replan is free to pick the other one — so the bot turns around, walks back, and turns around
		// again. Favouring the incumbent makes replanning a correction rather than a fresh opinion.
		double effectiveCost = favoured.contains(key)
				? cost * BotSettings.BACKTRACK_FAVOUR.get()
				: cost;
		double g = parent.g + effectiveCost;

		Node node = nodes.get(key);
		if (node == null) {
			node = new Node(pos.immutable());
			node.h = heuristic(pos);
			nodes.put(key, node);
		} else if (node.closed || g >= node.g) {
			return; // already settled, or we already know a cheaper way here
		}

		node.g = g;
		node.f = g + node.h;
		node.parent = parent;
		node.toBreak = toBreak;
		node.toPlace = toPlace;
		node.parkourDistance = parkourDistance;
		open.add(new Entry(key, node.f));
		trackFallback(node);
	}

	private ItemStack bestTool(BlockState state) {
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = 0.0f;
		for (ItemStack stack : tools) {
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = stack;
			}
		}
		return best;
	}

	private static final class Node {
		final BlockPos pos;
		double g = Double.POSITIVE_INFINITY;
		double h;
		double f = Double.POSITIVE_INFINITY;
		Node parent;
		List<BlockPos> toBreak = NO_BREAK;
		BlockPos toPlace;
		int parkourDistance;
		boolean closed;

		Node(BlockPos pos) {
			this.pos = pos;
		}
	}

	/**
	 * Immutable priority-queue entry.
	 *
	 * <p>The queue holds these rather than {@link Node}s because a node's {@code f} changes when a
	 * cheaper route to it is found. Mutating a value already inside a binary heap corrupts the heap
	 * invariant; instead a new entry is pushed and the outdated one is recognised and discarded
	 * when it surfaces.</p>
	 */
	private record Entry(long key, double f) implements Comparable<Entry> {
		@Override
		public int compareTo(Entry other) {
			return Double.compare(this.f, other.f);
		}
	}
}

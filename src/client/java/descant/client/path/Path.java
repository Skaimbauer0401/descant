package descant.client.path;

import java.util.ArrayList;
import java.util.List;

import descant.client.path.goal.Goal;
import net.minecraft.core.BlockPos;

/**
 * A computed route: an ordered list of block positions the player's feet should visit, each
 * annotated with the world edits required to get there.
 *
 * @param steps        the positions to walk, starting at the node after the player's current one
 * @param goal         the destination this path was computed towards
 * @param reachesGoal  {@code false} when the search ran out of budget and this path only leads to
 *                     the closest reachable point — the caller should replan on arrival
 */
public record Path(List<Step> steps, Goal goal, boolean reachesGoal) {

	/**
	 * One movement in a path.
	 *
	 * <p>A step records where it starts as well as where it ends. That is what lets the executor drive
	 * a movement precisely rather than merely steering at a point: knowing {@code from} gives the
	 * movement a <em>direction</em>, which in turn defines the launch block of a jump, the cell the
	 * player must have left before jumping, and where to back up to for a run-up. Inferring all of that
	 * from wherever the player happens to be standing — the previous approach — breaks down exactly
	 * when it matters, mid-air over a gap.</p>
	 *
	 * @param from            where the player's feet start (the previous step's {@code pos})
	 * @param pos             where the player's feet end up
	 * @param toBreak         blocks that must be mined before this step is walkable (usually empty)
	 * @param toPlace         block position that must be filled in to support this step, or {@code null}
	 * @param parkourDistance blocks a running jump must clear to reach {@code pos}, or {@code 0} for an
	 *                        ordinary walk/step — the executor drives a jump differently, and matches
	 *                        its power (sprint vs walk) to this distance
	 * @param cost            what the planner estimated this one movement would take, in ticks. The
	 *                        executor budgets each movement against its own estimate rather than a
	 *                        single global timeout, so a slow move (mining through a wall, a long
	 *                        fall) is given the time it genuinely needs while a one-block walk that
	 *                        stalls is noticed almost immediately
	 */
	public record Step(BlockPos from, BlockPos pos, List<BlockPos> toBreak, BlockPos toPlace,
			int parkourDistance, double cost) {

		public boolean needsWork() {
			return !toBreak.isEmpty() || toPlace != null;
		}

		/** Whether reaching this step needs a running jump across a gap. */
		public boolean parkour() {
			return parkourDistance > 0;
		}

		/** Whether the jump also gains a block of height (harder — always needs a sprint). */
		public boolean parkourAscend() {
			return parkour() && pos.getY() > from.getY();
		}

		/**
		 * The cardinal direction of a parkour jump, as a unit offset. Only meaningful when
		 * {@link #parkour()} — jumps are generated along cardinals only.
		 */
		public BlockPos direction() {
			return new BlockPos(
					Integer.signum(pos.getX() - from.getX()),
					0,
					Integer.signum(pos.getZ() - from.getZ()));
		}
	}

	/**
	 * Joins a continuation onto the end of this route, producing one seamless path.
	 *
	 * <p>This is what lets the bot plan its next segment <em>while still walking</em> the current one
	 * instead of arriving at the end of a partial path and standing still to think. The continuation
	 * must have been searched from this path's final position, so the join needs no stitching: the
	 * first step of {@code continuation} already starts where the last step of this path ends.</p>
	 */
	public Path concat(Path continuation) {
		List<Step> combined = new ArrayList<>(steps.size() + continuation.steps.size());
		combined.addAll(steps);
		combined.addAll(continuation.steps);
		return new Path(List.copyOf(combined), continuation.goal, continuation.reachesGoal);
	}

	/** Estimated ticks still to be spent from {@code fromIndex} to the end of the route. */
	public double remainingTicks(int fromIndex) {
		double total = 0.0;
		for (int index = Math.max(0, fromIndex); index < steps.size(); index++) {
			total += steps.get(index).cost();
		}
		return total;
	}

	/** Where this route ends up, or {@code null} when it is empty. */
	public BlockPos destination() {
		return steps.isEmpty() ? null : steps.get(steps.size() - 1).pos();
	}

	public boolean isEmpty() {
		return steps.isEmpty();
	}

	public int size() {
		return steps.size();
	}

	public Step step(int index) {
		return steps.get(index);
	}
}

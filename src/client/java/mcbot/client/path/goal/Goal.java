package mcbot.client.path.goal;

import mcbot.client.BotSettings;
import net.minecraft.core.BlockPos;

/**
 * What the bot is trying to achieve, expressed as a test and an estimate rather than a destination.
 *
 * <p>This is Baritone's central planning abstraction, and it is more useful than it first looks. A
 * pathfinder does not actually need to know <em>where</em> it is going — it only needs to be able to
 * ask "am I there yet?" and "roughly how much further?". Reducing the goal to those two questions
 * means the same unchanged search can pursue an exact block, a column of X/Z at any height, a depth
 * to dig down to, or a loose radius around something, with no special cases anywhere in A*.</p>
 *
 * <p>Implementations must keep {@link #heuristic} <b>admissible</b>: it may never exceed the true
 * remaining cost, or A* stops being able to trust that the first route it completes is the best one.
 * That is why the estimates here are built from the cheapest move the bot owns — sprinting flat out —
 * rather than from anything more representative.</p>
 */
public interface Goal {

	/** Whether a player standing with their feet at {@code pos} has arrived. */
	boolean isInGoal(BlockPos pos);

	/** Estimated remaining cost, in ticks, from {@code pos}. Must never overestimate. */
	double heuristic(BlockPos pos);

	/**
	 * A representative block for display — the goal marker, chat messages, and the "am I getting any
	 * closer?" stuck detector. For goals that are not a single point this is a stand-in, never the
	 * definition of the goal itself.
	 */
	BlockPos approximatePosition();

	/** Short human-readable form, for chat and the status line. */
	String describe();

	/**
	 * Horizontal travel cost as an <b>octile</b> distance: the exact cost of the cheapest route across
	 * open ground for a mover that has both cardinal and diagonal steps.
	 *
	 * <p>Straight-line (Euclidean) distance, which this replaced, is a weaker estimate — it is what a
	 * mover that could travel at any angle would pay, and the bot cannot. Going one block forward and
	 * two right costs one diagonal plus one straight, not the hypotenuse of a 1×2 triangle. Because
	 * octile is both admissible and <em>tighter</em>, A* expands measurably fewer nodes to reach the
	 * same answer: the estimate misleads it less often.</p>
	 */
	static double horizontalCost(double dx, double dz) {
		double alongX = Math.abs(dx);
		double alongZ = Math.abs(dz);
		double diagonal = Math.min(alongX, alongZ);
		double straight = Math.abs(alongX - alongZ);
		return (diagonal * BotSettings.DIAGONAL_MULTIPLIER + straight) * BotSettings.SPRINT_COST;
	}

	/**
	 * Vertical travel cost. Asymmetric on purpose: gravity does the work downwards, so descending is
	 * priced at the cost of falling, while every block gained upwards costs at least a jump.
	 */
	static double verticalCost(int fromY, int toY) {
		if (fromY > toY) {
			return (fromY - toY) * BotSettings.FALL_COST_PER_BLOCK;
		}
		return (toY - fromY) * BotSettings.JUMP_COST;
	}
}

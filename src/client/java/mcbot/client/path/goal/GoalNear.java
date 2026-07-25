package mcbot.client.path.goal;

import mcbot.client.BotSettings;
import net.minecraft.core.BlockPos;

/**
 * Get within {@code radius} blocks of somewhere, without caring exactly where you end up.
 *
 * <p>Useful whenever the destination is not somewhere you can actually stand: a mob that keeps
 * moving, a block you only need to be in reach of, a rough rendezvous point. Asking for the exact
 * position in those cases makes the search work hard for a precision that is about to be wrong
 * anyway.</p>
 */
public record GoalNear(BlockPos pos, int radius) implements Goal {

	public GoalNear {
		pos = pos.immutable();
	}

	@Override
	public boolean isInGoal(BlockPos other) {
		return other.distSqr(pos) <= (double) radius * radius;
	}

	@Override
	public double heuristic(BlockPos from) {
		double estimate = Goal.horizontalCost(pos.getX() - from.getX(), pos.getZ() - from.getZ())
				+ Goal.verticalCost(from.getY(), pos.getY());
		// Arriving anywhere on the boundary counts, so the last `radius` blocks of that estimate are
		// travel we will never actually make. Deducting them keeps the estimate admissible; leaving
		// them in would overestimate, and an overestimating heuristic can talk A* out of the best
		// route entirely.
		return Math.max(0.0, estimate - radius * BotSettings.SPRINT_COST.get());
	}

	@Override
	public BlockPos approximatePosition() {
		return pos;
	}

	@Override
	public String describe() {
		return "within " + radius + " of " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

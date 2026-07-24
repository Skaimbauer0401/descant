package mcbot.client.path.goal;

import net.minecraft.core.BlockPos;

/**
 * Get to a height, anywhere. Digging down to diamond level, or climbing back to the surface.
 *
 * @param referenceColumn an X/Z used only to draw the goal marker somewhere visible; the goal itself
 *                        is satisfied at this height anywhere in the world
 */
public record GoalYLevel(int y, BlockPos referenceColumn) implements Goal {

	public GoalYLevel {
		referenceColumn = referenceColumn.immutable();
	}

	@Override
	public boolean isInGoal(BlockPos pos) {
		return pos.getY() == y;
	}

	@Override
	public double heuristic(BlockPos from) {
		return Goal.verticalCost(from.getY(), y);
	}

	@Override
	public BlockPos approximatePosition() {
		return new BlockPos(referenceColumn.getX(), y, referenceColumn.getZ());
	}

	@Override
	public String describe() {
		return "y=" + y;
	}
}

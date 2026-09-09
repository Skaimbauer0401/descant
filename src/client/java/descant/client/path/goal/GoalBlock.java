package descant.client.path.goal;

import net.minecraft.core.BlockPos;

/** Stand on one exact block. The ordinary "go here" goal. */
public record GoalBlock(BlockPos pos) implements Goal {

	public GoalBlock {
		pos = pos.immutable();
	}

	@Override
	public boolean isInGoal(BlockPos other) {
		return other.equals(pos);
	}

	@Override
	public double heuristic(BlockPos from) {
		return Goal.horizontalCost(pos.getX() - from.getX(), pos.getZ() - from.getZ())
				+ Goal.verticalCost(from.getY(), pos.getY());
	}

	@Override
	public BlockPos approximatePosition() {
		return pos;
	}

	@Override
	public String describe() {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}

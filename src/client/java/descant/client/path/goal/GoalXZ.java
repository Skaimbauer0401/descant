package descant.client.path.goal;

import net.minecraft.core.BlockPos;

/**
 * Reach an X/Z column at whatever height the terrain happens to be.
 *
 * <p>The right goal for travelling any real distance, and the one a bot most often wants. Asking for
 * an exact block a thousand blocks away means naming a Y you cannot possibly know — get it wrong and
 * the search either tunnels down to it or pillars up to it on arrival. Leaving the height free lets
 * the route simply follow the ground.</p>
 *
 * @param referenceY a height used only for drawing the goal marker somewhere sensible; it plays no
 *                   part in whether the goal is satisfied
 */
public record GoalXZ(int x, int z, int referenceY) implements Goal {

	@Override
	public boolean isInGoal(BlockPos pos) {
		return pos.getX() == x && pos.getZ() == z;
	}

	@Override
	public double heuristic(BlockPos from) {
		return Goal.horizontalCost(x - from.getX(), z - from.getZ());
	}

	@Override
	public BlockPos approximatePosition() {
		return new BlockPos(x, referenceY, z);
	}

	@Override
	public String describe() {
		return "x=" + x + ", z=" + z;
	}
}

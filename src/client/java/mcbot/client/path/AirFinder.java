package mcbot.client.path;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Finds the nearest place the player could actually breathe.
 *
 * <p>Swimming straight up is only the right answer in open water. Under an overhang, inside a
 * flooded cave, or beneath a boat the ceiling simply stops you, and holding jump against it drowns
 * the player just as surely as doing nothing. A breadth-first search through the surrounding water
 * finds the genuinely closest air instead — which may well be sideways.</p>
 *
 * <p>Breadth-first rather than A*: there is no single destination to aim a heuristic at, the search
 * is small and tightly bounded, and BFS gives the nearest match by construction.</p>
 */
public final class AirFinder {

	private AirFinder() {
	}

	/**
	 * Nearest position whose head space holds air, or {@code null} if none is within budget.
	 *
	 * @param maxNodes bound on positions examined, so a large flooded cave cannot stall a tick
	 */
	public static BlockPos findNearestBreathable(WorldView world, BlockPos start, int maxNodes) {
		Deque<BlockPos> queue = new ArrayDeque<>();
		Set<Long> seen = new HashSet<>();

		queue.add(start);
		seen.add(start.asLong());
		int examined = 0;

		while (!queue.isEmpty() && examined < maxNodes) {
			BlockPos current = queue.poll();
			examined++;

			if (isBreathable(world, current)) {
				return current;
			}

			for (Direction direction : Direction.values()) {
				BlockPos next = current.relative(direction);
				if (!seen.add(next.asLong())) {
					continue;
				}
				// Only swim through space we can actually pass: water and air, never solid rock.
				if (world.isKnown(next) && world.isPassable(next)) {
					queue.add(next);
				}
			}
		}
		return null;
	}

	/**
	 * Whether standing here would let the player breathe: room for the body, and air rather than
	 * water at head height.
	 */
	private static boolean isBreathable(WorldView world, BlockPos feet) {
		return world.fitsAt(feet) && !world.isWater(feet.above());
	}
}

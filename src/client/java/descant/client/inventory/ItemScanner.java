package descant.client.inventory;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Looks for dropped items lying nearby that the bot could use.
 *
 * <p>Running out of building blocks halfway up a cliff used to end the journey. Very often the
 * missing material is right there on the ground — blocks the bot mined on its way, or a stack
 * dropped on death — so before giving up it is worth walking over and picking them up.</p>
 */
public final class ItemScanner {

	private ItemScanner() {
	}

	/**
	 * Nearest dropped item to {@code centre} within {@code radius}, or {@code null}.
	 *
	 * <p>Centred on a point rather than on the player, so a loot sweep can be tied to the spot
	 * where the block broke or the mob died. Searching around the player instead makes the bot
	 * wander off after any junk that happens to be lying nearby.</p>
	 */
	public static ItemEntity findNear(ClientLevel level, Vec3 centre, double radius,
			Predicate<ItemStack> wanted) {
		AABB search = new AABB(centre, centre).inflate(radius);
		List<ItemEntity> candidates = level.getEntitiesOfClass(ItemEntity.class, search,
				entity -> entity.isAlive() && wanted.test(entity.getItem()));

		ItemEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (ItemEntity candidate : candidates) {
			double distance = candidate.position().distanceToSqr(centre);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate;
			}
		}
		return nearest;
	}

	/**
	 * Nearest dropped item matching {@code wanted} within {@code radius}, or {@code null}.
	 *
	 * <p>Only entities the client already knows about are considered, which naturally limits this
	 * to loaded chunks — the same horizon the pathfinder plans within.</p>
	 */
	public static ItemEntity findNearby(ClientLevel level, LocalPlayer player,
			Predicate<ItemStack> wanted, double radius) {
		AABB search = player.getBoundingBox().inflate(radius);
		List<ItemEntity> candidates = level.getEntitiesOfClass(ItemEntity.class, search,
				entity -> entity.isAlive() && wanted.test(entity.getItem()));

		ItemEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (ItemEntity candidate : candidates) {
			double distance = candidate.distanceToSqr(player);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate;
			}
		}
		return nearest;
	}
}

package mcbot.client.api.actions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Facts about where the bot is, phrased once so that everything reporting them agrees.
 *
 * <p>{@code look} and {@code status} both answer "where am I", from different angles and at different
 * moments, and two copies of "what dimension is this" is two chances to word it differently — which
 * for a model reading both is two things to reconcile rather than one fact.</p>
 *
 * <p>Everything here is a read. Nothing costs an action, which is the point: a model that has to
 * spend a turn to find out whether it is night will not spend it, and will guess instead.</p>
 */
final class Surroundings {

	/** How far to sweep for entities. Beyond this the client does not reliably know about them. */
	static final double ENTITY_RANGE = 32.0;

	/** Kinds of entity to name before the list is cut short. */
	private static final int LISTED_KINDS = 8;

	private Surroundings() {
	}

	/** {@code overworld}, {@code the_nether}, {@code the_end} — or whatever a mod pack calls it. */
	static String dimension(ClientLevel level) {
		return level.dimension().identifier().getPath();
	}

	static String biome(ClientLevel level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("unknown biome");
	}

	/**
	 * The time, as both the raw tick and what it means.
	 *
	 * <p>13000 is meaningless on its own; "night" is what changes a decision, because that is when
	 * things start spawning. {@code getDefaultClockTime} is this dimension's own clock — the Nether and
	 * the End have no day cycle, and asking for the overworld's would report a sunrise nothing there
	 * can see.</p>
	 */
	static String clock(ClientLevel level) {
		long time = level.getDefaultClockTime() % 24000L;
		StringBuilder text = new StringBuilder(time < 12300 || time > 23850 ? "day" : "night");
		text.append(" (t=").append(time).append(")");
		if (level.isThundering()) {
			text.append(", thunderstorm");
		} else if (level.isRaining()) {
			text.append(", raining");
		}
		return text.toString();
	}

	/**
	 * What is hostile nearby, nearest first, or that nothing is.
	 *
	 * <p>Reported as {@link Enemy} rather than as the narrower {@code Monster} the self-defence code
	 * fights: this is about what the model should worry about, and a ghast or a slime is a problem
	 * whether or not the bot would walk up and hit it.</p>
	 *
	 * <p>Said explicitly when there are none. "No threats" is a fact worth stating — silence would be
	 * indistinguishable from not having looked.</p>
	 */
	static String threats(ClientLevel level, LocalPlayer player) {
		Map<String, int[]> tally = tally(level, player, entity -> entity instanceof Enemy);
		return tally.isEmpty()
				? "nothing hostile within " + (int) ENTITY_RANGE + " blocks"
				: describe(tally);
	}

	/** Every living thing nearby, hostile or not, tallied by kind. */
	static String creatures(ClientLevel level, LocalPlayer player) {
		Map<String, int[]> tally = tally(level, player, entity -> true);
		return tally.isEmpty()
				? "nothing within " + (int) ENTITY_RANGE + " blocks"
				: describe(tally);
	}

	/**
	 * Counts entities by kind, keeping the nearest one's distance.
	 *
	 * <p>Grouped rather than listed individually because a field of forty sheep is one fact, not
	 * forty, and the distance that matters is the closest.</p>
	 */
	private static Map<String, int[]> tally(ClientLevel level, LocalPlayer player,
			java.util.function.Predicate<Entity> wanted) {
		Map<String, int[]> tally = new LinkedHashMap<>();
		AABB box = player.getBoundingBox().inflate(ENTITY_RANGE);

		for (Entity entity : level.getEntitiesOfClass(Entity.class, box,
				candidate -> candidate != player && candidate.isAlive() && wanted.test(candidate))) {
			String kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
			int distance = (int) Math.round(entity.distanceTo(player));
			int[] seen = tally.computeIfAbsent(kind, key -> new int[] { 0, Integer.MAX_VALUE });
			seen[0]++;
			seen[1] = Math.min(seen[1], distance);
		}
		return tally;
	}

	private static String describe(Map<String, int[]> tally) {
		return tally.entrySet().stream()
				.sorted((a, b) -> Integer.compare(a.getValue()[1], b.getValue()[1]))
				.limit(LISTED_KINDS)
				.map(entry -> entry.getKey()
						+ (entry.getValue()[0] > 1 ? " x" + entry.getValue()[0] : "")
						+ " (" + entry.getValue()[1] + "m)")
				.collect(Collectors.joining(", "));
	}

	static String blockName(ClientLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}
}

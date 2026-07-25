package mcbot.client.api.actions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Describes the bot's surroundings.
 *
 * <p>{@code status} says what the bot is <em>doing</em>; this says where it <em>is</em>. The split
 * matters because they answer different questions and get called at different moments — "am I stuck?"
 * against "what is around me, and is it night?".</p>
 *
 * <p>Everything here is cheap and read-only, which is deliberate: a model that has to spend an action
 * to look around will not bother, and will guess instead.</p>
 */
public final class LookAction implements Action {

	/** How far to sweep for entities. Beyond this the client does not reliably know about them. */
	private static final double ENTITY_RANGE = 32.0;

	/** Kinds of entity to name before the list is cut short. */
	private static final int LISTED_KINDS = 8;

	@Override
	public String name() {
		return "look";
	}

	@Override
	public String description() {
		return "Describe the bot's surroundings: exact coordinates, which way it faces, the dimension "
				+ "and biome, the time of day and weather, the light level, what it is standing on, "
				+ "and every creature or player nearby. Costs nothing and changes nothing. Call it "
				+ "when you need to know where you are or what is around before deciding.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.optional("block", ParameterType.STRING,
				"Optionally, coordinates 'x y z' to report the block at instead of the bot's own "
						+ "surroundings — useful for checking a spot before building there."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		LocalPlayer player = context.player();
		ClientLevel level = context.minecraft().level;

		if (arguments.has("block")) {
			return describeBlock(level, arguments.getString("block"));
		}

		BlockPos feet = BlockPos.containing(player.position());
		StringBuilder text = new StringBuilder();

		text.append("At ").append((int) player.getX()).append(", ").append((int) player.getY())
				.append(", ").append((int) player.getZ())
				.append(" facing ").append(player.getDirection().getName());

		text.append(" | ").append(level.dimension().identifier().getPath());
		text.append(", ").append(biome(level, feet));

		// Time as both the raw tick and what it means. 13000 is meaningless on its own; "night" is
		// what actually changes a decision, since that is when things start spawning.
		//
		// getDefaultClockTime is this dimension's own clock — the nether and the end have no day
		// cycle, and asking for the overworld's would report a sunrise that nothing there can see.
		long time = level.getDefaultClockTime() % 24000L;
		text.append(" | ").append(time < 12300 || time > 23850 ? "day" : "night")
				.append(" (t=").append(time).append(")");
		if (level.isThundering()) {
			text.append(", thunderstorm");
		} else if (level.isRaining()) {
			text.append(", raining");
		}

		text.append(" | light ").append(level.getMaxLocalRawBrightness(feet)).append("/15");
		text.append(" | standing on ").append(blockName(level, feet.below()));
		text.append(", facing ").append(blockName(level, feet.relative(player.getDirection())));

		text.append(" | nearby: ").append(nearbyEntities(level, player));
		return ActionResult.ok(text.toString());
	}

	/** What is at one named spot, for checking somewhere before walking or building there. */
	private static ActionResult describeBlock(ClientLevel level, String raw) {
		String[] parts = raw.trim().split("[ ,]+");
		if (parts.length != 3) {
			return ActionResult.failed("'block' takes three numbers, like '100 64 -200'.");
		}
		BlockPos pos;
		try {
			pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return ActionResult.failed("'block' takes three whole numbers, like '100 64 -200'.");
		}
		if (!level.isLoaded(pos)) {
			// A real distinction: not loaded means "come closer and ask again", not "there is nothing".
			return ActionResult.failed("That spot is not loaded, so there is nothing to report. Travel "
					+ "closer and ask again.");
		}
		return ActionResult.ok(blockName(level, pos) + " at " + pos.getX() + ", " + pos.getY() + ", "
				+ pos.getZ() + ", with " + blockName(level, pos.above()) + " above and "
				+ blockName(level, pos.below()) + " below.");
	}

	/**
	 * Living things nearby, tallied by kind with the nearest one's distance.
	 *
	 * <p>Grouped rather than listed individually because a field of forty sheep is one fact, not
	 * forty, and the distance that matters is the closest.</p>
	 */
	private static String nearbyEntities(ClientLevel level, LocalPlayer player) {
		Map<String, int[]> tally = new LinkedHashMap<>();
		AABB box = player.getBoundingBox().inflate(ENTITY_RANGE);

		for (Entity entity : level.getEntitiesOfClass(Entity.class, box,
				candidate -> candidate != player && candidate.isAlive())) {
			String kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
			int distance = (int) Math.round(entity.distanceTo(player));
			int[] seen = tally.computeIfAbsent(kind, key -> new int[] { 0, Integer.MAX_VALUE });
			seen[0]++;
			seen[1] = Math.min(seen[1], distance);
		}
		if (tally.isEmpty()) {
			return "nothing within " + (int) ENTITY_RANGE + " blocks";
		}

		return tally.entrySet().stream()
				.sorted((a, b) -> Integer.compare(a.getValue()[1], b.getValue()[1]))
				.limit(LISTED_KINDS)
				.map(entry -> entry.getKey()
						+ (entry.getValue()[0] > 1 ? " x" + entry.getValue()[0] : "")
						+ " (" + entry.getValue()[1] + "m)")
				.collect(Collectors.joining(", "));
	}

	private static String biome(ClientLevel level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("unknown biome");
	}

	private static String blockName(ClientLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}
}

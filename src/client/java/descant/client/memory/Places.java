package descant.client.memory;

import descant.client.ConfigFiles;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;

/**
 * The bot's memory of places, kept across restarts.
 *
 * <p>A bot that has to be told where the base is every session has no memory at all. This is the
 * other half: places are written down when they are named or noticed, and they are still there
 * tomorrow. {@code recall} reads them, {@code goto} travels to them by name, and a language model
 * gets both through the ordinary action menu — so "go back to base" works without anybody having
 * looked up a coordinate.</p>
 *
 * <h2>Scoped to a world, deliberately</h2>
 *
 * <p>Every place records which save or server it belongs to, and nothing here will offer one from
 * somewhere else. Coordinates are meaningless across worlds, and a bot confidently walking to
 * another world's base coordinates is a worse failure than one that says it does not know.</p>
 *
 * <h2>One file, not one per world</h2>
 *
 * <p>The world is a field rather than part of the filename. Server addresses and save names contain
 * characters a filename cannot, and sanitising them is how two different worlds end up sharing a
 * file. Filtering on read costs nothing and cannot collide.</p>
 *
 * <p>Client thread only, like the rest of the mod.</p>
 */
public final class Places {

	private static final String FILE_NAME = "descant-places.json";

	/** How close an automatically noticed landmark must be to an existing one to count as the same. */
	private static final double SAME_PLACE_DISTANCE = 24.0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Loaded once, lazily, then kept — this is small and read far more often than it is written. */
	private static List<Place> places;

	private Places() {
	}

	public static Path file() {
		return ConfigFiles.of(FILE_NAME);
	}

	// ---------------------------------------------------------------- which world

	/**
	 * Which save or server the player is in, as a key to file places under.
	 *
	 * <p>The server address for multiplayer and the save name for singleplayer. Neither is perfect —
	 * a server that changes address becomes a new world, and two saves with the same name are one —
	 * but both are stable in the case that matters, which is playing the same world tomorrow.</p>
	 */
	public static String worldKey(Minecraft minecraft) {
		ServerData server = minecraft.getCurrentServer();
		if (server != null) {
			return "server:" + server.ip;
		}
		IntegratedServer local = minecraft.getSingleplayerServer();
		if (local != null) {
			return "save:" + local.getWorldData().getLevelName();
		}
		return "unknown";
	}

	/** Which dimension the player is in: {@code overworld}, {@code the_nether}, {@code the_end}. */
	public static String dimensionKey(Minecraft minecraft) {
		return minecraft.level == null ? "unknown" : minecraft.level.dimension().identifier().getPath();
	}

	// ---------------------------------------------------------------- reading

	/** Every place in the world the player is currently in, nearest first. */
	public static List<Place> here(Minecraft minecraft) {
		String world = worldKey(minecraft);
		List<Place> found = new ArrayList<>(load().stream()
				.filter(place -> place.world().equals(world))
				.toList());
		sortByDistance(minecraft, found);
		return found;
	}

	/**
	 * The place with this name in the current world, or {@code null}.
	 *
	 * <p>Case-insensitive, because nobody remembers whether they called it "Base" or "base", and a
	 * memory that only answers to exact capitalisation is a memory that mostly says "never heard of
	 * it".</p>
	 */
	public static Place named(Minecraft minecraft, String name) {
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		return here(minecraft).stream()
				.filter(place -> place.name().toLowerCase(Locale.ROOT).equals(wanted))
				.findFirst()
				.orElse(null);
	}

	/** Places whose name or kind contains {@code query}, nearest first. Empty query means all. */
	public static List<Place> matching(Minecraft minecraft, String query) {
		if (query == null || query.isBlank()) {
			return here(minecraft);
		}
		String wanted = query.trim().toLowerCase(Locale.ROOT);
		return here(minecraft).stream()
				.filter(place -> place.name().toLowerCase(Locale.ROOT).contains(wanted)
						|| place.kind().toLowerCase(Locale.ROOT).contains(wanted))
				.toList();
	}

	private static void sortByDistance(Minecraft minecraft, List<Place> found) {
		if (minecraft.player == null) {
			return;
		}
		BlockPos from = minecraft.player.blockPosition();
		String dimension = dimensionKey(minecraft);
		// Somewhere in another dimension sorts last rather than nowhere: it is still a real answer to
		// "where is the fortress", just not one you can walk to from here.
		found.sort(Comparator.comparingDouble(place -> {
			double distance = place.distanceFrom(from, dimension);
			return distance < 0.0 ? Double.MAX_VALUE : distance;
		}));
	}

	// ---------------------------------------------------------------- writing

	/**
	 * Writes a place down, replacing any of the same name in the same world.
	 *
	 * <p>Replacing rather than refusing: "remember base" said a second time means the base has moved,
	 * which is the only thing it could reasonably mean.</p>
	 *
	 * @return the place as stored
	 */
	public static Place remember(Minecraft minecraft, String name, String kind, BlockPos pos,
			boolean automatic) {
		Place place = new Place(name.trim(), kind.trim(), worldKey(minecraft), dimensionKey(minecraft),
				pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis(), automatic);

		List<Place> all = load();
		all.removeIf(existing -> existing.world().equals(place.world())
				&& existing.name().equalsIgnoreCase(place.name()));
		all.add(place);
		save();
		return place;
	}

	/**
	 * Records something the bot noticed by itself, unless it already knows about it.
	 *
	 * <p>Two guards, and both matter. A landmark within {@link #SAME_PLACE_DISTANCE} of one already
	 * recorded <em>of the same kind</em> is the same landmark seen from a different angle — a
	 * stronghold has twelve portal frames and would otherwise be twelve memories. And a name given by
	 * hand is never overwritten by an automatic sighting, because the bot noticing a beacon where your
	 * base is should not rename your base.</p>
	 *
	 * @return the new place, or {@code null} when this was already known
	 */
	public static Place notice(Minecraft minecraft, String kind, BlockPos pos) {
		String world = worldKey(minecraft);
		String dimension = dimensionKey(minecraft);

		for (Place existing : load()) {
			if (existing.world().equals(world) && existing.dimension().equals(dimension)
					&& existing.kind().equalsIgnoreCase(kind)
					&& Math.sqrt(existing.pos().distSqr(pos)) <= SAME_PLACE_DISTANCE) {
				return null;
			}
		}
		return remember(minecraft, uniqueName(minecraft, kind), kind, pos, true);
	}

	/** {@code stronghold}, then {@code stronghold 2}, and so on. */
	private static String uniqueName(Minecraft minecraft, String kind) {
		if (named(minecraft, kind) == null) {
			return kind;
		}
		for (int n = 2; n < 1000; n++) {
			String candidate = kind + " " + n;
			if (named(minecraft, candidate) == null) {
				return candidate;
			}
		}
		return kind + " " + System.currentTimeMillis();
	}

	/** Forgets one place by name in the current world. */
	public static boolean forget(Minecraft minecraft, String name) {
		String world = worldKey(minecraft);
		boolean removed = load().removeIf(place -> place.world().equals(world)
				&& place.name().equalsIgnoreCase(name.trim()));
		if (removed) {
			save();
		}
		return removed;
	}

	/** Forgets everything the bot noticed by itself in this world, keeping what was named by hand. */
	public static int forgetAutomatic(Minecraft minecraft) {
		String world = worldKey(minecraft);
		List<Place> all = load();
		int before = all.size();
		all.removeIf(place -> place.world().equals(world) && place.automatic());
		if (all.size() != before) {
			save();
		}
		return before - all.size();
	}

	// ---------------------------------------------------------------- the file

	private static List<Place> load() {
		if (places != null) {
			return places;
		}
		places = new ArrayList<>();

		Path file = file();
		if (!Files.isReadable(file)) {
			return places;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<Place> stored = GSON.fromJson(reader, new TypeToken<List<Place>>() {
			}.getType());
			if (stored != null) {
				// Nulls survive a hand-edited file with a stray comma, and one of them would take out
				// every read afterwards. Dropping them costs a place and saves the memory.
				stored.stream().filter(place -> place != null && place.name() != null)
						.forEach(places::add);
			}
		} catch (IOException | JsonSyntaxException | IllegalStateException e) {
			// A memory that cannot be read is the same as no memory, and refusing to start because of
			// it would be worse. The file is rewritten on the next change.
		}
		return places;
	}

	private static boolean save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(load(), writer);
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}

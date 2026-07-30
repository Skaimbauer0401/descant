package mcbot.client.settings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Every setting the bot has, looked up by name.
 *
 * <p>Settings add themselves here as they are constructed, so declaring one in
 * {@link mcbot.client.BotSettings} is all it takes to make it adjustable, listed and visible to the
 * model. Nothing has to be repeated in a second list, which is the usual way these registries drift
 * out of date.</p>
 *
 * <p>Registration happens at the end of each subclass constructor rather than in the shared base
 * one. That ordering is deliberate: registering from the base constructor would publish the setting
 * while its own value field was still zero.</p>
 *
 * <p>Client-thread only, like everything else here — the map is deliberately unsynchronised.</p>
 */
public final class SettingRegistry {

	/** Insertion-ordered, so listings come out grouped the way the settings are declared. */
	private static final Map<String, Setting> BY_NAME = new LinkedHashMap<>();

	private SettingRegistry() {
	}

	static void register(Setting setting) {
		Setting clash = BY_NAME.putIfAbsent(key(setting.name()), setting);
		if (clash != null) {
			// A duplicate name would make one of the two unreachable, and which one you got would
			// depend on class-loading order. Far better to fail at startup than to silently ignore
			// half the settings changes made later.
			throw new IllegalStateException("Two settings are both called '" + setting.name() + "'.");
		}
		GROUP_BY_NAME.put(key(setting.name()), currentGroup);
	}

	/** The setting with this name, or {@code null}. Case-insensitive. */
	public static Setting get(String name) {
		return BY_NAME.get(key(name));
	}

	/** Every setting, in declaration order. */
	public static Collection<Setting> all() {
		return BY_NAME.values();
	}

	/** Settings whose name contains {@code fragment}, for a partial {@code /mcbot set} lookup. */
	public static List<Setting> matching(String fragment) {
		String wanted = key(fragment);
		return BY_NAME.values().stream()
				.filter(setting -> key(setting.name()).contains(wanted))
				.toList();
	}

	/** Settings that have been changed from what they shipped with. */
	public static List<Setting> modified() {
		return BY_NAME.values().stream().filter(setting -> !setting.isDefault()).toList();
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	// ---------------------------------------------------------------- grouping

	/** The heading each setting was declared under. */
	private static final Map<String, String> GROUP_BY_NAME = new LinkedHashMap<>();

	private static final String UNGROUPED = "Other";

	private static String currentGroup = UNGROUPED;

	/**
	 * Files every setting declared after this call under {@code heading}.
	 *
	 * <p>A marker rather than a constructor argument, because the settings are already written in
	 * sections and the sections already have headings — as comments. This turns those comments into
	 * something the settings screen can read, at the cost of one line per section rather than one
	 * argument per setting.</p>
	 *
	 * <p>Only meaningful while a class of settings is initialising, since it is read by
	 * {@link #register} and nothing else. A class declaring settings should open with a call to this;
	 * one that does not gets whatever heading was left behind by the last class that did.</p>
	 */
	public static void group(String heading) {
		currentGroup = heading;
	}

	/** The heading this setting is filed under. Groups come out in declaration order from {@link #all}. */
	public static String groupOf(Setting setting) {
		return GROUP_BY_NAME.getOrDefault(key(setting.name()), UNGROUPED);
	}

	// ---------------------------------------------------------------- remembering

	private static final String FILE_NAME = "mcbot.properties";

	/** Where changed settings are kept: {@code config/mcbot.properties} beside the game. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * Applies whatever was saved last time.
	 *
	 * <p>Called once at startup, after {@link mcbot.client.BotSettings#load()} has brought the settings
	 * into existence — an empty registry would have nothing to apply anything to.</p>
	 *
	 * <p>Entries that no longer make sense are skipped rather than complained about: a setting removed
	 * in a later version, or a value that has since been given a narrower range, are both the mod's
	 * doing rather than the reader's, and neither is worth an error message at startup. The file is
	 * rewritten on the next change, which drops them.</p>
	 */
	public static void load() {
		Path file = file();
		if (!Files.isReadable(file)) {
			return;
		}
		Properties stored = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			stored.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			return;
		}

		for (String name : stored.stringPropertyNames()) {
			Setting setting = get(name);
			if (setting == null) {
				continue;
			}
			try {
				setting.parse(stored.getProperty(name));
			} catch (RuntimeException e) {
				// Was valid when it was written and is not now. Leaving it at the default is the only
				// sensible reading of a value the setting itself refuses.
			}
		}
	}

	/**
	 * Puts every setting back to the value it shipped with, and forgets the ones that were changed.
	 *
	 * <p>Saving afterwards rather than letting each reset save for itself: eighty writes of a file that
	 * is shrinking by one line each time is eighty chances to leave it half-written, and the only state
	 * anyone cares about is the one at the end.</p>
	 *
	 * @return whether the file was written
	 */
	public static boolean resetAll() {
		BY_NAME.values().forEach(Setting::reset);
		return save();
	}

	/**
	 * Writes the settings that differ from their defaults.
	 *
	 * <p>Only those. Recording all seventy-nine would freeze every default at whatever it happened to
	 * be the first time the file was written, so a later improvement to one would never reach anyone
	 * who had never touched it — the settings would silently stop being maintainable.</p>
	 *
	 * @return whether it was written; a caller that has just promised a change should say so if not
	 */
	static boolean save() {
		StringBuilder text = new StringBuilder("""
				# mcbot settings. Written whenever one is changed with /mcbot set or /mcbot toggle.
				#
				# Only settings changed from their defaults appear here, so anything left out follows
				# whatever the current version thinks is best. Delete a line to go back to that.
				# Names this version does not know are ignored.

				""");
		for (Setting setting : modified()) {
			text.append(setting.name()).append('=').append(setting.asString()).append('\n');
		}

		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}

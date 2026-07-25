package mcbot.client.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
}

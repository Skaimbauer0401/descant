package descant.client.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import descant.client.settings.SettingChoice;

/**
 * The arguments handed to an action, as text.
 *
 * <p>Text rather than typed objects because the two callers both start there — a chat command has
 * parsed strings, and a model emits JSON. Converting once, here, means the "that is not a number"
 * message is written once and reads the same however the call arrived.</p>
 *
 * <p>The getters throw {@link ActionException} rather than returning a default, so a malformed call
 * stops before it reaches the bot. Silently substituting a default would let a model believe it had
 * sent the bot to one place while it walked to another.</p>
 */
public final class Arguments {

	private static final Arguments EMPTY = new Arguments(Map.of());

	private final Map<String, String> values;

	private Arguments(Map<String, String> values) {
		this.values = values;
	}

	public static Arguments none() {
		return EMPTY;
	}

	public static Arguments of(Map<String, String> values) {
		return new Arguments(Map.copyOf(values));
	}

	/**
	 * Builds arguments from alternating names and values.
	 *
	 * <p>A {@code null} value drops the argument rather than storing "null", which lets a caller pass
	 * an optional argument straight through without first testing whether it has one.</p>
	 */
	public static Arguments of(Object... nameThenValue) {
		if (nameThenValue.length % 2 != 0) {
			throw new IllegalArgumentException("Arguments.of takes alternating names and values.");
		}
		Map<String, String> built = new LinkedHashMap<>();
		for (int i = 0; i < nameThenValue.length; i += 2) {
			Object value = nameThenValue[i + 1];
			if (value != null) {
				built.put(String.valueOf(nameThenValue[i]), String.valueOf(value));
			}
		}
		return new Arguments(built);
	}

	public boolean has(String name) {
		String raw = values.get(name);
		return raw != null && !raw.isBlank();
	}

	/** The arguments as they came in, for logging and for echoing a call back to a model. */
	public Map<String, String> raw() {
		return values;
	}

	// ---------------------------------------------------------------- typed reads

	public String getString(String name) {
		String raw = values.get(name);
		if (raw == null || raw.isBlank()) {
			throw new ActionException("'" + name + "' is required.");
		}
		return raw.trim();
	}

	public String getString(String name, String fallback) {
		return has(name) ? values.get(name).trim() : fallback;
	}

	public int getInt(String name) {
		String raw = getString(name);
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			throw new ActionException("'" + name + "' must be a whole number, not '" + raw + "'.");
		}
	}

	public int getInt(String name, int fallback) {
		return has(name) ? getInt(name) : fallback;
	}

	public double getDouble(String name) {
		String raw = getString(name);
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			throw new ActionException("'" + name + "' must be a number, not '" + raw + "'.");
		}
	}

	public double getDouble(String name, double fallback) {
		return has(name) ? getDouble(name) : fallback;
	}

	public boolean getBoolean(String name, boolean fallback) {
		if (!has(name)) {
			return fallback;
		}
		String raw = getString(name).toLowerCase(Locale.ROOT);
		return switch (raw) {
			case "true", "yes", "on", "1" -> true;
			case "false", "no", "off", "0" -> false;
			default -> throw new ActionException(
					"'" + name + "' must be true or false, not '" + raw + "'.");
		};
	}

	/**
	 * Reads one of a fixed set of options, matched on {@link SettingChoice#key()}.
	 *
	 * @param options every option, so a wrong answer can be told what the right ones were
	 */
	public <E extends Enum<E> & SettingChoice> E getChoice(String name, E[] options, E fallback) {
		if (!has(name)) {
			return fallback;
		}
		String wanted = getString(name).toLowerCase(Locale.ROOT);
		for (E option : options) {
			if (option.key().equals(wanted)) {
				return option;
			}
		}
		StringBuilder allowed = new StringBuilder();
		for (E option : options) {
			allowed.append(allowed.isEmpty() ? "" : ", ").append(option.key());
		}
		throw new ActionException("'" + name + "' must be one of: " + allowed + " — not '" + wanted + "'.");
	}
}

package mcbot.client.settings;

import java.util.List;
import java.util.Locale;

/** A setting that is simply on or off. */
public final class BooleanSetting extends Setting {

	private final boolean defaultValue;
	private boolean value;

	public BooleanSetting(String name, boolean defaultValue, String description) {
		super(name, description);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
		SettingRegistry.register(this);
	}

	public boolean get() {
		return value;
	}

	public void set(boolean newValue) {
		this.value = newValue;
	}

	/**
	 * Flips the value and hands back the new one, for the toggle commands.
	 *
	 * <p>Saves, for the same reason {@link Setting#change} does: a toggle is a deliberate change, and
	 * one that forgets itself overnight is the more confusing kind of broken.</p>
	 */
	public boolean toggle() {
		value = !value;
		SettingRegistry.save();
		return value;
	}

	@Override
	public String type() {
		return "boolean";
	}

	@Override
	public String domain() {
		return "true or false";
	}

	@Override
	public List<String> options() {
		return List.of("true", "false");
	}

	@Override
	public String asString() {
		return String.valueOf(value);
	}

	@Override
	public String defaultAsString() {
		return String.valueOf(defaultValue);
	}

	@Override
	public void parse(String raw) {
		// Accept the words people and models actually write, not only Java's two.
		switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "true", "yes", "on", "1" -> value = true;
			case "false", "no", "off", "0" -> value = false;
			default -> throw new IllegalArgumentException(
					name() + " is true or false, not '" + raw + "'.");
		}
	}

	@Override
	public boolean isDefault() {
		return value == defaultValue;
	}

	@Override
	public void reset() {
		value = defaultValue;
	}
}

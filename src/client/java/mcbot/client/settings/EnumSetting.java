package mcbot.client.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A setting whose value is one of a fixed set of named options.
 *
 * @param <E> the enum of options, each of which describes itself via {@link SettingChoice}
 */
public final class EnumSetting<E extends Enum<E> & SettingChoice> extends Setting {

	private final E defaultValue;
	private final E[] options;
	private E value;

	public EnumSetting(String name, E defaultValue, String description) {
		super(name, description);
		this.defaultValue = defaultValue;
		this.options = defaultValue.getDeclaringClass().getEnumConstants();
		this.value = defaultValue;
		SettingRegistry.register(this);
	}

	public E get() {
		return value;
	}

	public void set(E newValue) {
		this.value = newValue;
	}

	/** The options, so a command can offer them for tab completion and a model can be shown them. */
	public List<E> options() {
		return List.of(options);
	}

	@Override
	public String type() {
		return "choice";
	}

	@Override
	public String domain() {
		return "one of: " + Arrays.stream(options)
				.map(option -> option.key() + " (" + option.describe() + ")")
				.collect(Collectors.joining(", "));
	}

	@Override
	public String asString() {
		return value.key();
	}

	@Override
	public String defaultAsString() {
		return defaultValue.key();
	}

	@Override
	public void parse(String raw) {
		String wanted = raw.trim().toLowerCase(Locale.ROOT);
		for (E option : options) {
			if (option.key().equals(wanted)) {
				value = option;
				return;
			}
		}
		throw new IllegalArgumentException(name() + " must be " + Arrays.stream(options)
				.map(SettingChoice::key)
				.collect(Collectors.joining(" or ")) + ", not '" + raw + "'.");
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

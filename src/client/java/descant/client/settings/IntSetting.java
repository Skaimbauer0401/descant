package descant.client.settings;

/** A setting holding a whole number — a tick count, a node budget, a radius in blocks. */
public final class IntSetting extends Setting {

	private final int defaultValue;
	private final int minimum;
	private final int maximum;
	private int value;

	/** A setting that may not go negative — see {@link DoubleSetting#DoubleSetting}. */
	public IntSetting(String name, int defaultValue, String description) {
		this(name, defaultValue, 0, Integer.MAX_VALUE, description);
	}

	public IntSetting(String name, int defaultValue, int minimum, int maximum, String description) {
		super(name, description);
		this.defaultValue = defaultValue;
		this.minimum = minimum;
		this.maximum = maximum;
		this.value = defaultValue;
		SettingRegistry.register(this);
	}

	/** The current value. Safe to call in a hot loop — a plain field read, with no boxing. */
	public int get() {
		return value;
	}

	public void set(int newValue) {
		if (newValue < minimum || newValue > maximum) {
			throw new IllegalArgumentException(name() + " must be " + domain() + " (got " + newValue + ").");
		}
		this.value = newValue;
	}

	@Override
	public String type() {
		return "integer";
	}

	@Override
	public String domain() {
		if (minimum == 0 && maximum == Integer.MAX_VALUE) {
			return "0 or more";
		}
		if (maximum == Integer.MAX_VALUE) {
			return minimum + " or more";
		}
		return "between " + minimum + " and " + maximum;
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
		int parsed;
		try {
			parsed = Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(name() + " takes a whole number, not '" + raw + "'.");
		}
		set(parsed);
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

package mcbot.client.settings;

/**
 * A setting holding free text — a model name, a host address.
 *
 * <p>The only setting kind with nothing to validate beyond "not blank". That is the honest position:
 * whether {@code qwen3:14b} is a model you have pulled is not a question this class can answer, and
 * pretending otherwise with a list of known names would go stale the week after it was written. The
 * check that matters happens when the request is made, and the error comes back from Ollama saying
 * exactly which name it did not recognise.</p>
 */
public final class StringSetting extends Setting {

	private final String defaultValue;
	private final String hint;
	private String value;

	/**
	 * @param hint what a valid value looks like, e.g. "a model name". Shown to whoever is choosing
	 *             one, which — for the AI settings — includes the model itself
	 */
	public StringSetting(String name, String defaultValue, String hint, String description) {
		super(name, description);
		this.defaultValue = defaultValue;
		this.hint = hint;
		this.value = defaultValue;
		SettingRegistry.register(this);
	}

	public String get() {
		return value;
	}

	public void set(String newValue) {
		if (newValue == null || newValue.isBlank()) {
			throw new IllegalArgumentException(name() + " cannot be empty.");
		}
		this.value = newValue.trim();
	}

	@Override
	public String type() {
		return "string";
	}

	@Override
	public String domain() {
		return hint;
	}

	@Override
	public String asString() {
		return value;
	}

	@Override
	public String defaultAsString() {
		return defaultValue;
	}

	@Override
	public void parse(String raw) {
		set(raw);
	}

	@Override
	public boolean isDefault() {
		return value.equals(defaultValue);
	}

	@Override
	public void reset() {
		value = defaultValue;
	}
}

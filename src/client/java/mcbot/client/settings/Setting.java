package mcbot.client.settings;

/**
 * One knob that can be changed while the game is running.
 *
 * <p>These replace what used to be {@code static final} constants. The values are identical; what
 * changes is that they can now be read and written by name, which is what lets both
 * {@code /mcbot set} and — more importantly — a language model adjust the bot's behaviour without
 * anyone recompiling it.</p>
 *
 * <p>Every setting carries its own {@link #description()} and {@link #domain()}. That is not
 * decoration: a model choosing a value has nothing to go on but this text, so a setting whose
 * purpose is not written down here is one the model cannot use correctly.</p>
 *
 * <p>Subclasses are specialised per primitive rather than being one generic {@code Setting<T>},
 * because these are read inside the A* inner loop. A generic version would hand back a boxed
 * {@code Double} on every read and rely on the JIT to undo it; a {@code double}-returning
 * {@code get()} simply never boxes.</p>
 */
public abstract class Setting {

	private final String name;
	private final String description;

	protected Setting(String name, String description) {
		this.name = name;
		this.description = description;
	}

	/** The name this setting is known by to a person or a model, in camelCase. */
	public final String name() {
		return name;
	}

	/** What the setting does, in a sentence. Shown by {@code /mcbot set} and given to the model. */
	public final String description() {
		return description;
	}

	/** The kind of value: {@code number}, {@code integer}, {@code boolean} or {@code choice}. */
	public abstract String type();

	/** Which values are accepted, in words — a range, or the list of allowed names. */
	public abstract String domain();

	/** The current value, formatted for display. */
	public abstract String asString();

	/** The value this setting was born with, formatted for display. */
	public abstract String defaultAsString();

	/**
	 * Applies a value written as text.
	 *
	 * @throws IllegalArgumentException with a message explaining what was wrong, which is shown to
	 *         whoever (or whatever) supplied the bad value so it can correct itself
	 */
	public abstract void parse(String raw);

	/** Whether the setting still holds the value it shipped with. */
	public abstract boolean isDefault();

	/** Puts the shipped value back. */
	public abstract void reset();

	@Override
	public String toString() {
		return name + " = " + asString();
	}
}

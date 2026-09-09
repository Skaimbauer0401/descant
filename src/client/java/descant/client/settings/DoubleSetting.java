package descant.client.settings;

import java.util.Locale;

/** A setting holding a decimal number — a cost in ticks, a distance in blocks, an angle. */
public final class DoubleSetting extends Setting {

	private final double defaultValue;
	private final double minimum;
	private final double maximum;
	private double value;

	/**
	 * A setting that may not go negative.
	 *
	 * <p>The default lower bound is zero rather than "anything", because every decimal tunable the bot
	 * has is a quantity — a cost, a distance, a fraction, an angle. A negative cost is not an unusual
	 * choice, it is a broken one: it makes A* prefer longer routes and can stop the search terminating
	 * at all. Rejecting it at the door is cheaper than debugging it afterwards.</p>
	 */
	public DoubleSetting(String name, double defaultValue, String description) {
		this(name, defaultValue, 0.0, Double.MAX_VALUE, description);
	}

	public DoubleSetting(String name, double defaultValue, double minimum, double maximum,
			String description) {
		super(name, description);
		this.defaultValue = defaultValue;
		this.minimum = minimum;
		this.maximum = maximum;
		this.value = defaultValue;
		SettingRegistry.register(this);
	}

	/** The current value. Safe to call in a hot loop — it is a plain field read, with no boxing. */
	public double get() {
		return value;
	}

	/** The current value as a float, for the vanilla APIs that deal in them — yaw, pitch, health. */
	public float getFloat() {
		return (float) value;
	}

	public void set(double newValue) {
		if (!Double.isFinite(newValue)) {
			throw new IllegalArgumentException(name() + " must be a real number.");
		}
		if (newValue < minimum || newValue > maximum) {
			throw new IllegalArgumentException(name() + " must be " + domain() + " (got " + newValue + ").");
		}
		this.value = newValue;
	}

	@Override
	public String type() {
		return "number";
	}

	@Override
	public String domain() {
		if (minimum == 0.0 && maximum == Double.MAX_VALUE) {
			return "0 or more";
		}
		if (maximum == Double.MAX_VALUE) {
			return minimum + " or more";
		}
		return "between " + trim(minimum) + " and " + trim(maximum);
	}

	@Override
	public String asString() {
		return trim(value);
	}

	@Override
	public String defaultAsString() {
		return trim(defaultValue);
	}

	@Override
	public void parse(String raw) {
		double parsed;
		try {
			parsed = Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(name() + " takes a number, not '" + raw + "'.");
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

	/** Prints 4.0 as "4" and 4.633 as "4.633", so the listing stays readable. */
	private static String trim(double number) {
		return number == Math.rint(number) && Math.abs(number) < 1e15
				? String.valueOf((long) number)
				: String.format(Locale.ROOT, "%s", number);
	}
}

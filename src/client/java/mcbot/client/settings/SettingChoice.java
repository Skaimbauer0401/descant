package mcbot.client.settings;

/**
 * Implemented by enums that are exposed as a setting, so each option can introduce itself.
 *
 * <p>The alternative — deriving the option name from {@link Enum#name()} — reads fine in a chat
 * command but is not enough for a model, which has to choose between the options knowing only what
 * it is told about them. {@code LOOKING_AT} is a label; "the container the crosshair is pointing at"
 * is a reason to pick it.</p>
 */
public interface SettingChoice {

	/** What you type to select this option — lowercase, no underscores. */
	String key();

	/** What choosing this option means, in a short phrase. */
	String describe();
}

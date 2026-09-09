package descant.client.settings;

import java.util.List;

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

	/**
	 * Names this option used to be called, still accepted but never offered.
	 *
	 * <p>Renaming an option is otherwise a silent data loss: the saved file still holds the old word,
	 * the setting refuses to parse it, and {@link SettingRegistry#load} leaves the default in place
	 * without saying so. The setting has quietly become something else, and the only clue is the bot
	 * behaving differently after a restart.</p>
	 *
	 * <p>Not included in {@link Setting#options()}, so nothing offers a name that is on its way out —
	 * these are read on the way in only.</p>
	 */
	default List<String> aliases() {
		return List.of();
	}
}

package mcbot.client.api;

/**
 * What happened when an action ran.
 *
 * <p>Always a sentence, never an exception. A chat command prints it; a model reads it and decides
 * what to do next, so "No iron_ore within 320 blocks of here" has to carry enough for it to try
 * somewhere else, and a bare {@code false} would not.</p>
 *
 * @param ok      whether the action did what was asked
 * @param message what happened, in a sentence
 * @param quiet   whether the bot has already said this in chat, so a command should not repeat it.
 *                The message is still filled in either way — the model has not "heard" anything and
 *                needs telling regardless
 */
public record ActionResult(boolean ok, String message, boolean quiet) {

	public static ActionResult ok(String message) {
		return new ActionResult(true, message, false);
	}

	public static ActionResult failed(String message) {
		return new ActionResult(false, message, false);
	}

	/** Succeeded, and the bot has already reported it in chat itself. */
	public static ActionResult okQuiet(String message) {
		return new ActionResult(true, message, true);
	}

	/** Failed, and the bot has already reported why in chat itself. */
	public static ActionResult failedQuiet(String message) {
		return new ActionResult(false, message, true);
	}
}

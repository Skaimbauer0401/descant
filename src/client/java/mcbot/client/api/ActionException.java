package mcbot.client.api;

/**
 * Thrown when an action cannot run with the arguments it was given.
 *
 * <p>The message is written to be read by whoever supplied the arguments — which, once a model is
 * driving, means it is read by the model and used to fix the call. So it says what was wrong and
 * what would be right, rather than merely reporting that something failed.</p>
 */
public class ActionException extends RuntimeException {

	public ActionException(String message) {
		super(message);
	}
}

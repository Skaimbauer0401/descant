package descant.client.api;

import java.util.List;

/**
 * One thing the bot can be told to do.
 *
 * <p>This is the whole vocabulary. A chat command invokes these, and — the reason the interface
 * looks the way it does — so will a language model: {@link #name()}, {@link #description()} and
 * {@link #parameters()} together are exactly the tool definition a model is handed, and
 * {@link #run} is what happens when it picks one.</p>
 *
 * <p>That is also the safety property. A model never touches the keyboard, the world, or the
 * controller's internals; it can only choose from actions written and tested here. Widening what it
 * can do means adding an action on purpose, which is a much easier thing to review than a model
 * that can do anything and is merely asked not to.</p>
 *
 * <p>An action describes itself completely, so nothing has to be repeated in a schema file that
 * would then need keeping in step.</p>
 */
public interface Action {

	/** What the action is called. Lowercase, no spaces — this is what a model writes to call it. */
	String name();

	/**
	 * What the action does, and when to reach for it.
	 *
	 * <p>Written for a reader who cannot see the code, because that is the only reader it has.</p>
	 */
	String description();

	/**
	 * The arguments it accepts. Empty for actions that take none.
	 *
	 * <p><b>Must not touch the world, the player or the controller.</b> Unlike {@link #run}, this is
	 * called off the client thread — the AI agent builds the tool schema on its worker thread before
	 * it has anything to hand over with. Reading a registry or a static list is fine; reading the
	 * level is a crash, and an intermittent one, since it depends on what the client is doing at the
	 * time.</p>
	 */
	default List<Parameter> parameters() {
		return List.of();
	}

	/**
	 * Does the thing.
	 *
	 * @throws ActionException if the arguments do not make sense; the registry turns this into a
	 *         failed {@link ActionResult} carrying the explanation
	 */
	ActionResult run(ActionContext context, Arguments arguments);
}

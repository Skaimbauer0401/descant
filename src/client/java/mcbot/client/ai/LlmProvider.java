package mcbot.client.ai;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonArray;

/**
 * Somewhere a model can be asked what to do next.
 *
 * <p>The conversation lives inside the {@link Session} rather than being passed in and out, because
 * providers disagree about what a conversation even <em>is</em> — Ollama takes a flat list of
 * messages with a {@code tool_calls} field, while Anthropic takes content blocks. Handing the
 * history back and forth would force one of those shapes on the other and leave the loser
 * translating. Owning it privately means a second provider is a new class and nothing else.</p>
 */
public interface LlmProvider {

	/** What this provider is, for chat: e.g. {@code "ollama qwen3:14b"}. */
	String describe();

	/**
	 * Opens a conversation.
	 *
	 * @param systemPrompt the standing instructions
	 * @param tools        the action menu, as {@link mcbot.client.api.ActionRegistry#schema()}
	 */
	Session begin(String systemPrompt, JsonArray tools);

	/**
	 * One conversation, holding its own history.
	 *
	 * <p>Called from a background thread — never the client thread, since every method here blocks on
	 * the network.</p>
	 */
	interface Session {

		/** Says something to the model and waits for its answer. */
		LlmReply say(String text) throws IOException;

		/** Reports what the actions did, and waits for whatever it wants to do next. */
		LlmReply report(List<ToolOutcome> outcomes) throws IOException;
	}

	/**
	 * What running one call actually did.
	 *
	 * @param call   the call as the model made it
	 * @param result what happened, in a sentence — including the failures, which are the ones the
	 *               model most needs to read
	 */
	record ToolOutcome(ToolCall call, String result) {
	}
}

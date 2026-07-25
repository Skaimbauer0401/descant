package mcbot.client.ai;

import java.util.List;

/**
 * What the model said back.
 *
 * <p>Either it wants to do something, or it is finished and talking to the player. Both at once is
 * allowed and useful — a model often narrates its reasoning alongside the call it is making.</p>
 *
 * @param text  anything it said in words, possibly empty
 * @param calls the actions it wants run; empty means the turn is over
 */
public record LlmReply(String text, List<ToolCall> calls) {

	public LlmReply {
		calls = List.copyOf(calls);
	}

	public boolean wantsToAct() {
		return !calls.isEmpty();
	}
}

package descant.client.ai;

import java.util.Map;
import java.util.stream.Collectors;

import descant.client.api.Arguments;

/**
 * One action the model has decided to take.
 *
 * <p>Arguments are carried as text, matching {@link Arguments}: the model emits JSON where a
 * coordinate may arrive as {@code 100}, {@code 100.0} or {@code "100"} depending on the model and
 * its mood, and flattening all three to a string means the checking happens in one place that
 * already knows how to complain usefully about it.</p>
 *
 * @param id        the model's own handle for this call, echoed back with the result so it can match
 *                  them up. Empty when the provider does not use them
 * @param name      the action to run
 * @param arguments the arguments, by name
 */
public record ToolCall(String id, String name, Map<String, String> arguments) {

	public Arguments toArguments() {
		return Arguments.of(arguments);
	}

	/** Renders the call the way it would be written, for the running commentary in chat. */
	public String describe() {
		return name + "(" + arguments.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.collect(Collectors.joining(", ")) + ")";
	}
}

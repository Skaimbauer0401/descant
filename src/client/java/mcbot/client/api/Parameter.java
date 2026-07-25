package mcbot.client.api;

import java.util.List;

/**
 * One argument an action accepts.
 *
 * <p>The {@code description} is not documentation — it is the only thing a model has to go on when
 * deciding what to put here. "the block to look for, as a Minecraft id like {@code iron_ore}" leads
 * to a working call; "the target" leads to a guess.</p>
 *
 * @param name        what the argument is called
 * @param type        what kind of value it takes
 * @param description what it means, written for someone who cannot see the code
 * @param required    whether the action refuses to run without it
 * @param choices     the only accepted values, or empty when anything of the right type will do
 */
public record Parameter(String name, ParameterType type, String description, boolean required,
		List<String> choices) {

	public Parameter {
		choices = List.copyOf(choices);
	}

	public static Parameter required(String name, ParameterType type, String description) {
		return new Parameter(name, type, description, true, List.of());
	}

	public static Parameter optional(String name, ParameterType type, String description) {
		return new Parameter(name, type, description, false, List.of());
	}

	/** An argument that must be one of a fixed set of words. */
	public static Parameter choice(String name, String description, boolean required,
			List<String> choices) {
		return new Parameter(name, ParameterType.STRING, description, required, choices);
	}
}

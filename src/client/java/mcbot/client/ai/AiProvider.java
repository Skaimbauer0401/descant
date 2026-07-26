package mcbot.client.ai;

import mcbot.client.settings.SettingChoice;

/**
 * Where the model that drives the bot comes from.
 *
 * <p>Three, not two, because they differ in the things a person actually weighs: whether the
 * conversation leaves the machine, what it costs, and how good the answers are. Ollama's local and
 * cloud models are one provider in code — a cloud model is proxied through the same daemon — but
 * treating them as one choice here would hide the only distinction that matters to whoever is
 * picking.</p>
 */
public enum AiProvider implements SettingChoice {

	/** An Ollama model running on this machine. Free, private, and the weakest of the three. */
	LOCAL("local", "a model on this machine via Ollama — free and private"),

	/** An Ollama cloud model, proxied through the local daemon. Needs {@code ollama signin}. */
	CLOUD("cloud", "an Ollama cloud model, proxied through the local daemon"),

	/**
	 * Anthropic's API.
	 *
	 * <p>Needs an {@code ANTHROPIC_API_KEY} in the environment. This is the paid API and is billed by
	 * the token — a Claude Pro or Max subscription does not cover it, and there is no way to make it.
	 * Haiku is cheap enough that the distinction is mostly academic, but it is a real one.</p>
	 */
	CLAUDE("claude", "Anthropic's API — needs ANTHROPIC_API_KEY, billed per token");

	private final String key;
	private final String description;

	AiProvider(String key, String description) {
		this.key = key;
		this.description = description;
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public String describe() {
		return description;
	}

	/** Opens a client for this provider. */
	public LlmProvider create() {
		return this == CLAUDE ? new ClaudeProvider() : new OllamaProvider();
	}
}

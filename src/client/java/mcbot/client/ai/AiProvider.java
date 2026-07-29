package mcbot.client.ai;

import mcbot.client.settings.SettingChoice;

/**
 * Where the model that drives the bot comes from.
 *
 * <p>Split by the things a person actually weighs — whether the conversation leaves the machine,
 * what it costs, and how good the answers are — rather than by how the code is arranged. Ollama's
 * local and cloud models are one provider class, since a cloud model is proxied through the same
 * daemon, but treating them as one choice here would hide the only distinction that matters to
 * whoever is picking.</p>
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
	CLAUDE("claude", "Anthropic's API — needs ANTHROPIC_API_KEY, billed per token"),

	/**
	 * Google's Gemini API.
	 *
	 * <p>Needs a {@code GEMINI_API_KEY} in the environment. Unlike Anthropic's, this one has a real
	 * free tier — rate-limited per minute and per day, but the limits are far above what driving a bot
	 * uses — which makes it the cheapest way to put a strong model behind the bot.</p>
	 */
	GEMINI("gemini", "Google's Gemini API — needs GEMINI_API_KEY, has a free tier");

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
		return switch (this) {
			case CLAUDE -> new ClaudeProvider();
			case GEMINI -> new GeminiProvider();
			// Local and cloud are one class: a cloud model is proxied through the same daemon, and the
			// only difference that reaches the code is the model name.
			case LOCAL, CLOUD -> new OllamaProvider();
		};
	}
}

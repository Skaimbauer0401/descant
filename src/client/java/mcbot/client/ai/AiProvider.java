package mcbot.client.ai;

import mcbot.client.BotSettings;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingChoice;
import mcbot.client.settings.StringSetting;

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

	/**
	 * The setting this provider reads its model name from.
	 *
	 * <p>A method rather than a field on the enum: {@link mcbot.client.BotSettings} constructs the
	 * {@code aiProvider} setting from these constants, so a field initialised from BotSettings would be
	 * read during that class's own initialisation and find it half-built.</p>
	 */
	public StringSetting modelSetting() {
		return switch (this) {
			case LOCAL -> BotSettings.AI_MODEL;
			case CLOUD -> BotSettings.AI_CLOUD_MODEL;
			case CLAUDE -> BotSettings.AI_CLAUDE_MODEL;
			case GEMINI -> BotSettings.AI_GEMINI_MODEL;
		};
	}

	/**
	 * A warning, when the setting just changed is a model name the active provider does not read.
	 *
	 * <p>There are four model settings and they differ by one word in the middle, which is one word too
	 * few. Setting {@code aiModel} while running Gemini changes something real, succeeds, reports
	 * success, and has no effect on anything — and the only evidence is a quota message naming a model
	 * nobody chose. Cheap to detect and expensive to work out, so it is said at the point of the
	 * mistake.</p>
	 *
	 * @return the clause to append, or empty when the change was the relevant one
	 */
	public static String inertNote(Setting changed) {
		AiProvider active = BotSettings.AI_PROVIDER.get();
		for (AiProvider provider : values()) {
			if (provider.modelSetting() == changed && provider != active) {
				return " Note: aiProvider is '" + active.key() + "', which reads "
						+ active.modelSetting().name() + " — so this has no effect on anything until "
						+ "'/mcbot set aiProvider " + provider.key() + "'.";
			}
		}
		return "";
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

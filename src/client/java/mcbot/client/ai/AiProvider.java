package mcbot.client.ai;

import java.util.List;

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
		};
	}

	/**
	 * The names an API key for this provider may go by, best first — empty when none is needed.
	 *
	 * <p>Lets anything ask whether a provider is usable without knowing which one it is asking about,
	 * and without a key ever leaving {@link ApiKeys}. The lists themselves live on the providers that
	 * use them, so there is one place a name is written down.</p>
	 */
	public List<String> keyNames() {
		return switch (this) {
			case LOCAL, CLOUD -> List.of();
			case CLAUDE -> ClaudeProvider.KEY_NAMES;
		};
	}

	/**
	 * A warning, when the setting just changed is a model name the active provider does not read.
	 *
	 * <p>The model settings differ by one word in the middle, which is one word too few. Setting
	 * {@code aiModel} while running Claude changes something real, succeeds, reports success, and has
	 * no effect on anything — and the only evidence is an error message naming a model nobody chose.
	 * Cheap to detect and expensive to work out, so it is said at the point of the mistake.</p>
	 *
	 * @return the clause to append, or empty when the change was the relevant one
	 */
	public static String inertNote(Setting changed) {
		AiProvider reader = readerOf(changed);
		AiProvider active = BotSettings.AI_PROVIDER.get();
		if (reader == null || reader == active) {
			return "";
		}
		return " Note: aiProvider is '" + active.key() + "', which reads "
				+ active.modelSetting().name() + " — so this has no effect on anything until "
				+ "'/mcbot set aiProvider " + reader.key() + "'.";
	}

	/**
	 * The provider that reads this setting for its model name, or {@code null} if none does.
	 *
	 * <p>The question behind {@link #inertNote}, separated from the sentence it produces so that the
	 * settings screen — which has a colour to say it with and no room for a sentence — can ask it
	 * too.</p>
	 */
	public static AiProvider readerOf(Setting setting) {
		for (AiProvider provider : values()) {
			if (provider.modelSetting() == setting) {
				return provider;
			}
		}
		return null;
	}

	/** Opens a client for this provider. */
	public LlmProvider create() {
		return switch (this) {
			case CLAUDE -> new ClaudeProvider();
			// Local and cloud are one class: a cloud model is proxied through the same daemon, and the
			// only difference that reaches the code is the model name.
			case LOCAL, CLOUD -> new OllamaProvider();
		};
	}
}

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
 * whoever is picking. In the other direction, three of these share one class: OpenAI's
 * chat-completions API is what Alibaba and Moonshot both implement, so what differs between them is
 * an address and a key rather than any code.</p>
 *
 * <p>Everything a provider needs is on the constant: its address, the names its key goes by, which
 * setting holds its model name, and where to ask what models it has. That is what lets the settings
 * screen offer a provider it knows nothing in particular about — and what makes adding the next one
 * a constant here rather than a search for the places that need teaching.</p>
 */
public enum AiProvider implements SettingChoice {

	/** An Ollama model running on this machine. Free, private, and the weakest of the set. */
	OLLAMA("ollama", "Ollama", "a model on this machine via Ollama — free and private"),

	/** An Ollama cloud model, proxied through the local daemon. Needs {@code ollama signin}. */
	CLOUD("cloud", "Ollama cloud", "an Ollama cloud model, proxied through the local daemon"),

	/**
	 * Anthropic's API.
	 *
	 * <p>The paid API, billed by the token — a Claude Pro or Max subscription does not cover it, and
	 * there is no way to make it. Haiku is cheap enough that the distinction is mostly academic, but
	 * it is a real one.</p>
	 */
	CLAUDE("claude", "Claude", "Anthropic's Claude — needs ANTHROPIC_API_KEY, billed per token"),

	/** OpenAI's API — the ChatGPT models, billed by the token. */
	CHATGPT("chatgpt", "ChatGPT", "OpenAI's models — needs OPENAI_API_KEY, billed per token"),

	/** Alibaba's Qwen, through DashScope's OpenAI-compatible endpoint. */
	QWEN("qwen", "Qwen", "Alibaba's Qwen via DashScope — needs DASHSCOPE_API_KEY"),

	/** Moonshot's Kimi, through their OpenAI-compatible endpoint. */
	KIMI("kimi", "Kimi", "Moonshot's Kimi — needs MOONSHOT_API_KEY");

	private final String key;
	private final String label;
	private final String description;

	AiProvider(String key, String label, String description) {
		this.key = key;
		this.label = label;
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

	/** The name as it is written down elsewhere — "ChatGPT", not "chatgpt". For the screen. */
	public String label() {
		return label;
	}

	/**
	 * The setting this provider reads its model name from.
	 *
	 * <p>A method rather than a field on the enum: {@link mcbot.client.BotSettings} constructs the
	 * {@code aiProvider} setting from these constants, so a field initialised from BotSettings would be
	 * read during that class's own initialisation and find it half-built. The same goes for every other
	 * accessor here that reaches into BotSettings.</p>
	 */
	public StringSetting modelSetting() {
		return switch (this) {
			case OLLAMA -> BotSettings.AI_OLLAMA_MODEL;
			case CLOUD -> BotSettings.AI_CLOUD_MODEL;
			case CLAUDE -> BotSettings.AI_CLAUDE_MODEL;
			case CHATGPT -> BotSettings.AI_CHATGPT_MODEL;
			case QWEN -> BotSettings.AI_QWEN_MODEL;
			case KIMI -> BotSettings.AI_KIMI_MODEL;
		};
	}

	/**
	 * The names an API key for this provider may go by, best first — empty when none is needed.
	 *
	 * <p>Lets anything ask whether a provider is usable without knowing which one it is asking about,
	 * and without a key ever leaving {@link ApiKeys}. Where a vendor's own SDK reads more than one
	 * name, all of them are accepted: somebody who already has one set should not have to find out
	 * which this mod happens to prefer.</p>
	 */
	public List<String> keyNames() {
		return switch (this) {
			// Both go through the local daemon, which authenticates itself — 'ollama signin' once, and
			// nothing this code ever holds.
			case OLLAMA, CLOUD -> List.of();
			case CLAUDE -> List.of("ANTHROPIC_API_KEY");
			case CHATGPT -> List.of("OPENAI_API_KEY");
			case QWEN -> List.of("DASHSCOPE_API_KEY", "QWEN_API_KEY");
			case KIMI -> List.of("MOONSHOT_API_KEY", "KIMI_API_KEY");
		};
	}

	/** Whether reaching this provider needs a key somebody has to supply. */
	public boolean needsKey() {
		return !keyNames().isEmpty();
	}

	/** Where a key comes from and what it costs, in a sentence, for when there is not one. */
	public String keyHelp() {
		return switch (this) {
			case OLLAMA, CLOUD -> "";
			case CLAUDE -> "Create one at console.anthropic.com — note this is the paid API, billed per "
					+ "token, and a Claude Pro subscription does not cover it.";
			case CHATGPT -> "Create one at platform.openai.com/api-keys — this is the paid API, separate "
					+ "from a ChatGPT Plus subscription, which does not cover it.";
			case QWEN -> "Create one at bailian.console.aliyun.com, on the international endpoint.";
			case KIMI -> "Create one at platform.moonshot.ai — billed per token.";
		};
	}

	/**
	 * The root of this provider's HTTP API.
	 *
	 * <p>Ollama's is a setting rather than a constant, because it is the one that might genuinely be
	 * somewhere else — another machine on the network, or a different port.</p>
	 */
	public String baseUrl() {
		return switch (this) {
			case OLLAMA, CLOUD -> BotSettings.AI_HOST.get().replaceAll("/+$", "");
			case CLAUDE -> "https://api.anthropic.com/v1";
			case CHATGPT -> "https://api.openai.com/v1";
			// The international endpoint. The mainland one (dashscope.aliyuncs.com) takes different
			// keys, so pointing the wrong one at a key fails in a way that looks like a bad key.
			case QWEN -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
			case KIMI -> "https://api.moonshot.ai/v1";
		};
	}

	/** Whether this provider speaks OpenAI's chat-completions API, whoever is hosting it. */
	public boolean openAiCompatible() {
		return this == CHATGPT || this == QWEN || this == KIMI;
	}

	/** Opens a client for this provider. */
	public LlmProvider create() {
		return switch (this) {
			case CLAUDE -> new ClaudeProvider();
			case CHATGPT, QWEN, KIMI -> new OpenAiProvider(this);
			// Local and cloud are one class: a cloud model is proxied through the same daemon, and the
			// only difference that reaches the code is the model name.
			case OLLAMA, CLOUD -> new OllamaProvider();
		};
	}

	// ---------------------------------------------------------------- the six-settings trap

	/**
	 * A warning, when the setting just changed is a model name the active provider does not read.
	 *
	 * <p>The model settings differ by one word in the middle, which is one word too few. Setting
	 * {@code aiOllamaModel} while running Claude changes something real, succeeds, reports success, and
	 * has no effect on anything — and the only evidence is an error message naming a model nobody
	 * chose. Cheap to detect and expensive to work out, so it is said at the point of the mistake.</p>
	 *
	 * <p>The settings screen sidesteps it entirely by showing one model row that follows the provider.
	 * This is for the command, where there is no such thing.</p>
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
	 * settings screen — which has a colour to say it with, and one row standing in for all six — can
	 * ask it too.</p>
	 */
	public static AiProvider readerOf(Setting setting) {
		for (AiProvider provider : values()) {
			if (provider.modelSetting() == setting) {
				return provider;
			}
		}
		return null;
	}
}

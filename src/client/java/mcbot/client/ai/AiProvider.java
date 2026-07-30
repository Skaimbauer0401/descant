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

	/**
	 * An Ollama model running on this machine. Free and private, and the weakest of the set.
	 *
	 * <p>Marked not recommended, which is not a criticism of Ollama. A 12B model that fits on a home
	 * graphics card has to choose the right function out of twenty and get their order right, and that
	 * is the exact task it is worst at — the failures are not wrong answers but wrong actions, which
	 * cost real time in the world. It is the right choice for working offline and the wrong one for
	 * anything else.</p>
	 */
	OLLAMA("ollama-local", "Ollama local", List.of("ollama", "local"),
			"a model on this machine via Ollama — free, private, and the weakest of the set"),

	/**
	 * An Ollama cloud model, proxied through the local daemon. Needs {@code ollama signin}.
	 *
	 * <p>The recommendation. A strong model at cloud speed, reached through the same daemon and the
	 * same one-off sign-in, with no per-token bill to watch — the best value on the list by a distance,
	 * which is why it leads it.</p>
	 */
	CLOUD("ollama-cloud", "Ollama cloud", List.of("cloud"),
			"an Ollama cloud model, proxied through the local daemon — the best value here"),

	/**
	 * Anthropic's API.
	 *
	 * <p>The paid API, billed by the token — a Claude Pro or Max subscription does not cover it, and
	 * there is no way to make it. Haiku is cheap enough that the distinction is mostly academic, but
	 * it is a real one.</p>
	 */
	CLAUDE("claude", "Claude", List.of(),
			"Anthropic's Claude — needs ANTHROPIC_API_KEY, billed per token"),

	/** OpenAI's API — the ChatGPT models, billed by the token. */
	CHATGPT("chatgpt", "ChatGPT", List.of("openai"),
			"OpenAI's models — needs OPENAI_API_KEY, billed per token"),

	/** Alibaba's Qwen, through DashScope's OpenAI-compatible endpoint. */
	QWEN("qwen", "Qwen", List.of("dashscope"),
			"Alibaba's Qwen via DashScope — needs DASHSCOPE_API_KEY"),

	/** Moonshot's Kimi, through their OpenAI-compatible endpoint. */
	KIMI("kimi", "Kimi", List.of("moonshot"),
			"Moonshot's Kimi — needs MOONSHOT_API_KEY");

	private final String key;
	private final String label;
	private final List<String> aliases;
	private final String description;

	AiProvider(String key, String label, List<String> aliases, String description) {
		this.key = key;
		this.label = label;
		this.aliases = aliases;
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
	 * Names this option used to go by, still accepted.
	 *
	 * <p>These keys have been renamed twice. Without this, a saved {@code aiProvider=cloud} stops
	 * parsing and silently reverts to the default — the setting quietly becoming something else on a
	 * version upgrade, which is the failure this project has spent the most time on.</p>
	 */
	@Override
	public List<String> aliases() {
		return aliases;
	}

	/** The name as it is written down elsewhere — "ChatGPT", not "chatgpt". For the screen. */
	public String label() {
		return label;
	}

	/** Whether this is the one to pick if you have no particular reason to pick another. */
	public boolean recommended() {
		return this == CLOUD;
	}

	/**
	 * Why this provider is or is not the one to pick, in a sentence, for the chooser.
	 *
	 * <p>Separate from {@link #describe()}, which says what a provider <em>is</em>. This says what
	 * choosing it would mean, which is the question actually being asked at the moment it is read.</p>
	 */
	public String advice() {
		return switch (this) {
			case CLOUD -> "The best value here: a strong model at cloud speed, through the same daemon "
					+ "and the same one-off 'ollama signin', with no per-token bill. Start here.";
			case OLLAMA -> "Not recommended. A model small enough to run at home has to pick the right "
					+ "function out of twenty and get their order right, and that is the task it is worst "
					+ "at — the failures are wrong actions rather than wrong answers. Right for working "
					+ "offline, wrong for anything else.";
			case CLAUDE -> "Excellent at choosing between functions, and Haiku is cheap. Billed per token, "
					+ "and a Claude Pro subscription does not cover API use.";
			case CHATGPT -> "Reliable and widely known. Billed per token, separate from a ChatGPT Plus "
					+ "subscription, which does not cover API use.";
			case QWEN -> "Strong for the money, and the international DashScope endpoint includes a "
					+ "starting allowance.";
			case KIMI -> "The K2 models are genuinely good at agentic work. Billed per token.";
		};
	}

	/**
	 * Model names worth offering before anything has been fetched, best first.
	 *
	 * <p>At least three each, so the chooser is never an empty box, and the first is the one to pick —
	 * chosen across providers to land at roughly the same capability as {@code minimax-m3}, which is
	 * the level this job actually needs: reliable function choice, not deep reasoning.</p>
	 *
	 * <p><b>These go stale, and are marked as such when they do.</b> Hosted model ids are retired
	 * faster than a mod gets recompiled. {@link ModelCatalogue} asks the provider for the real list and
	 * anything here the provider did not confirm is shown dimmed as "unlisted" rather than quietly
	 * offered — a name that is merely unadvertised often still works, and one that has been withdrawn
	 * should not look identical to one that has not.</p>
	 */
	public List<String> suggestedModels() {
		return switch (this) {
			// Measured rather than assumed: gemma4:12b was the smallest local model that picked the right
			// actions in the right order. qwen3:14b is stronger if the memory is there.
			case OLLAMA -> List.of("gemma4:12b", "qwen3:14b", "mistral-nemo:12b", "llama3.1:8b");
			// minimax-m3 is the reference the rest of this list is calibrated against.
			case CLOUD -> List.of("minimax-m3:cloud", "gpt-oss:120b-cloud", "nemotron-3-ultra:cloud",
					"gemma4:cloud");
			case CLAUDE -> List.of("claude-haiku-4-5-20251001", "claude-sonnet-4-5", "claude-opus-4-5");
			case CHATGPT -> List.of("gpt-4.1-mini", "gpt-4.1", "gpt-5-mini", "gpt-5");
			case QWEN -> List.of("qwen-plus", "qwen-max", "qwen-turbo");
			case KIMI -> List.of("kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-32k",
					"moonshot-v1-8k");
		};
	}

	/** The model to start with for this provider — the first suggestion, and each setting's default. */
	public String recommendedModel() {
		return suggestedModels().getFirst();
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

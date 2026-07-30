package mcbot.client.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * What models a provider will actually accept, asked of the provider.
 *
 * <p>Every one of these vendors publishes a list endpoint, and every one of them retires model ids
 * faster than a mod gets recompiled. A hardcoded list would be wrong within weeks and wrong in the
 * worst way — silently, offering a name that no longer resolves. So the list is fetched, keyed to the
 * same API key the requests use, which also means it answers a second question for free: a key that
 * cannot list models cannot run a model either, so a failure here <em>is</em> the diagnosis.</p>
 *
 * <p>Fetched off the client thread and published into a map the screen reads each frame. No
 * callbacks: a screen that is already drawing sixty times a second does not need to be told when
 * something has changed, it needs somewhere to look.</p>
 *
 * <p>Cached for the session. The list does not move while the game is open, and re-asking on every
 * visit to the screen would spend a network round trip on a question already answered — there is a
 * refresh button for the case where it genuinely has changed.</p>
 */
public final class ModelCatalogue {

	/** How far along the fetch for one provider is. */
	public enum State {
		/** Never asked. */
		UNASKED,
		/** Asked, waiting. */
		LOADING,
		/** Answered, and {@link #models} holds the answer. */
		READY,
		/** Asked and failed; {@link #problem} says why. */
		FAILED
	}

	private record Entry(State state, List<String> models, String problem) {
	}

	private static final Entry UNASKED = new Entry(State.UNASKED, List.of(), "");

	private static final Map<AiProvider, Entry> BY_PROVIDER = new ConcurrentHashMap<>();

	/**
	 * Bits of an id that mean it is not a model that can hold a conversation.
	 *
	 * <p>A heuristic, and the only hardcoded list here. OpenAI's endpoint returns everything the key
	 * can reach — embeddings, speech, images, moderation — which is sixty-odd entries of which a
	 * handful can drive a bot, and a cycling button with the wrong fifty on it is worse than useless.
	 * Nothing is lost by being over-eager: {@code /mcbot set} still takes any name at all.</p>
	 */
	private static final List<String> NOT_CHAT = List.of(
			"embedding", "embed", "whisper", "tts", "audio", "realtime", "dall-e", "image",
			"moderation", "davinci", "babbage", "rerank", "ocr", "search", "transcribe", "sora");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private ModelCatalogue() {
	}

	// ---------------------------------------------------------------- reading

	/** How the fetch for this provider is going. */
	public static State state(AiProvider provider) {
		return BY_PROVIDER.getOrDefault(provider, UNASKED).state();
	}

	/** The models this provider offers — empty until a fetch has succeeded. */
	public static List<String> models(AiProvider provider) {
		return BY_PROVIDER.getOrDefault(provider, UNASKED).models();
	}

	/** Why the fetch failed, in a sentence. Empty unless the state is {@link State#FAILED}. */
	public static String problem(AiProvider provider) {
		return BY_PROVIDER.getOrDefault(provider, UNASKED).problem();
	}

	// ---------------------------------------------------------------- asking

	/** Fetches the list unless it has already been fetched or is on its way. */
	public static void request(AiProvider provider) {
		if (state(provider) == State.UNASKED) {
			refresh(provider);
		}
	}

	/**
	 * Fetches the list again whatever happened last time.
	 *
	 * <p>What the refresh button does, and what pasting a key does — a list that failed for want of a
	 * key should not stay failed once there is one.</p>
	 */
	public static void refresh(AiProvider provider) {
		BY_PROVIDER.put(provider, new Entry(State.LOADING, models(provider), ""));

		// A plain thread rather than a pool: this happens when somebody opens a screen, at most once
		// per provider, and a pool would be a lifetime to manage for six requests a session.
		Thread worker = new Thread(() -> {
			try {
				List<String> models = fetch(provider);
				BY_PROVIDER.put(provider, models.isEmpty()
						? new Entry(State.FAILED, List.of(), provider.label()
								+ " listed no models this key can reach.")
						: new Entry(State.READY, models, ""));
			} catch (IOException | RuntimeException e) {
				BY_PROVIDER.put(provider, new Entry(State.FAILED, List.of(),
						e.getMessage() == null ? e.toString() : e.getMessage()));
			}
		}, "mcbot model catalogue " + provider.key());
		worker.setDaemon(true);
		worker.start();
	}

	// ---------------------------------------------------------------- fetching

	private static List<String> fetch(AiProvider provider) throws IOException {
		if (provider == AiProvider.CLOUD) {
			// The daemon lists what is installed locally; the cloud catalogue is not exposed through it
			// at all. Saying so is better than an empty list that reads as "your key is wrong".
			throw new IOException("Ollama does not publish its cloud model list through the daemon. "
					+ "See ollama.com/search?c=cloud and type the name in — anything ending ':cloud'.");
		}

		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create(provider.baseUrl()
						+ (provider == AiProvider.OLLAMA ? "/api/tags" : "/models")))
				.timeout(Duration.ofSeconds(20))
				.GET();

		if (provider.needsKey()) {
			String key = ApiKeys.require(provider.label(), provider.keyHelp(), provider.keyNames());
			if (provider == AiProvider.CLAUDE) {
				request.header("x-api-key", key).header("anthropic-version", "2023-06-01");
			} else {
				request.header("Authorization", "Bearer " + key);
			}
		}

		HttpResponse<String> response;
		try {
			response = HTTP.send(request.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (ConnectException e) {
			throw new IOException(provider == AiProvider.OLLAMA
					? "Can't reach Ollama at " + provider.baseUrl() + ". Start it with 'ollama serve'."
					: "Can't reach " + provider.label() + " at " + provider.baseUrl() + ".");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while asking " + provider.label() + " for its models.");
		}

		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new IOException(provider.label() + " rejected the key. " + provider.keyHelp());
		}
		if (response.statusCode() != 200) {
			throw new IOException(provider.label() + " returned HTTP " + response.statusCode()
					+ " when asked for its models.");
		}

		try {
			return read(provider, JsonParser.parseString(response.body()).getAsJsonObject());
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new IOException(provider.label() + " sent something that wasn't a model list.");
		}
	}

	/** Ollama answers with {@code models[].name}; everything else with {@code data[].id}. */
	private static List<String> read(AiProvider provider, JsonObject body) {
		boolean ollama = provider == AiProvider.OLLAMA;
		JsonArray entries = body.getAsJsonArray(ollama ? "models" : "data");
		if (entries == null) {
			return List.of();
		}

		List<String> names = new ArrayList<>();
		for (JsonElement element : entries) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonElement name = element.getAsJsonObject().get(ollama ? "name" : "id");
			if (name == null || name.isJsonNull()) {
				continue;
			}
			String id = name.getAsString();
			// Ollama only ever lists models that are installed, so everything it says is usable.
			if (ollama || isChat(id)) {
				names.add(id);
			}
		}
		names.sort(String::compareToIgnoreCase);
		return List.copyOf(names);
	}

	private static boolean isChat(String id) {
		String lower = id.toLowerCase(Locale.ROOT);
		return NOT_CHAT.stream().noneMatch(lower::contains);
	}
}

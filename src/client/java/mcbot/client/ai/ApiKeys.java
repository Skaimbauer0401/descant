package mcbot.client.ai;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import net.minecraft.client.Minecraft;

/**
 * Where the paid providers get their keys.
 *
 * <p>Two sources, checked in order: the environment first, then {@code config/mcbot-keys.properties}
 * beside the game. The environment is the better place for a secret and stays the winner when both
 * hold a key — but it is genuinely awkward on Windows, where {@code setx} only reaches processes
 * started <em>after</em> it and so does nothing for a launcher that is already open. A file the game
 * reads is the version that works the first time.</p>
 *
 * <p>Read fresh on every request rather than cached at startup, so a key pasted into the file takes
 * effect immediately. That costs one small file read per network round trip, which is nothing next to
 * the round trip, and it removes the restart that made this awkward in the first place.</p>
 *
 * <p>What is never done with a key: put it in a {@link mcbot.client.BotSettings setting}. Settings
 * are listed by {@code /mcbot set}, echoed into chat and written into the action schema the model
 * itself reads — three separate ways for a credential to end up somewhere it cannot be taken back
 * from. Only the <em>path</em> is ever shown.</p>
 */
public final class ApiKeys {

	private static final String FILE_NAME = "mcbot-keys.properties";

	private ApiKeys() {
	}

	/** Where the keys file lives: {@code .minecraft/config/mcbot-keys.properties}. */
	public static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
	}

	/**
	 * Finds a key, or explains where to put one.
	 *
	 * @param service what is being reached, for the message: {@code "Gemini"}
	 * @param help    one sentence on where a key comes from and what it costs
	 * @param names   the accepted variable names, best first — several because the official SDKs of
	 *                some providers read more than one, and someone who already has one set should not
	 *                have to discover which this mod happens to prefer
	 */
	public static String require(String service, String help, List<String> names) throws IOException {
		String key = find(names);
		if (key == null) {
			throw new IOException(complaint(service, help, names.getFirst()));
		}
		return key;
	}

	/**
	 * Whether a key is available under any of these names.
	 *
	 * <p>For telling someone their chosen provider will not work <em>before</em> they ask it to do
	 * something — the settings screen does this next to the provider it belongs to. Deliberately gives
	 * back a yes or no rather than the key: nothing outside this class needs to hold one, and a method
	 * that hands them out is a method that ends up being called from somewhere that prints things.</p>
	 */
	public static boolean has(List<String> names) {
		return find(names) != null;
	}

	/** The first key set under any of these names, environment before file, or {@code null}. */
	private static String find(List<String> names) {
		for (String name : names) {
			String found = System.getenv(name);
			if (isSet(found)) {
				return found.trim();
			}
		}

		Properties stored = read();
		for (String name : names) {
			// Trimmed because a key copied out of a web page arrives with a trailing space often enough
			// to be worth handling, and the failure it causes is an unexplained 401.
			String found = stored.getProperty(name);
			if (isSet(found)) {
				return found.trim();
			}
		}
		return null;
	}

	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}

	/** The stored keys, or nothing at all — a missing or unreadable file is not worth an error here. */
	private static Properties read() {
		Properties stored = new Properties();
		Path file = file();
		if (!Files.isReadable(file)) {
			return stored;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			stored.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			// Fall through to the "no key" message, which names the file. Reporting a parse error
			// separately would only add a second thing to read on the way to the same fix.
			return new Properties();
		}
		return stored;
	}

	/**
	 * Says what is missing, and leaves behind a file to put it in.
	 *
	 * <p>The template is written here rather than at startup so that a player who never uses a paid
	 * provider never gets a file they did not ask for — and so the message can point at something that
	 * exists by the time it is read.</p>
	 */
	private static String complaint(String service, String help, String name) {
		Path file = file();
		String created = template(file);
		return "No " + name + " found, so " + service + " cannot be reached. " + help
				+ " Put it in " + file + " on a line reading '" + name + "=your-key'"
				+ created + " — it is read fresh each time, so there is no need to restart. "
				+ "An environment variable of the same name works too, but on Windows 'setx' only "
				+ "reaches programs started afterwards, so the file is the simpler route. "
				+ "'/mcbot set aiProvider local' works offline meanwhile.";
	}

	/** Writes the empty template if there is no file yet. Returns a clause for the message. */
	private static String template(Path file) {
		if (Files.exists(file)) {
			return "";
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, """
					# API keys for mcbot. One per line, as NAME=value.
					#
					# Everything after the '=' is part of the value, so no quotes and no comment on the
					# same line. Read fresh on every request: a key pasted in here works immediately,
					# with no restart. An environment variable of the same name wins over this file.
					#
					# Anyone holding one of these can spend on your account. Keep the file out of
					# screenshots, and out of any folder you share or commit.

					# Google, from aistudio.google.com/apikey — has a free tier
					#GEMINI_API_KEY=

					# Anthropic, from console.anthropic.com — billed per token; Pro does not cover it
					#ANTHROPIC_API_KEY=
					""", StandardCharsets.UTF_8);
			return " (just created, with the names filled in)";
		} catch (IOException e) {
			// Nothing to do about it: the message still names the right path, and creating the file by
			// hand works exactly as well.
			return "";
		}
	}
}

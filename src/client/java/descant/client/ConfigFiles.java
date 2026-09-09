package descant.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where a config file lives — and the one-time move from the name it used to have.
 *
 * <p>This mod was called {@code mcbot} before it was called Descant, and a rename that quietly leaves
 * every saved setting, API key and remembered place behind is the failure this project has spent the
 * most time on wearing yet another disguise: the file on disk holds the old name, nothing reads it,
 * defaults are used instead, and the only clue is the bot behaving like a fresh install after an
 * update. The same shape as {@link descant.client.settings.SettingChoice} needing aliases, one level
 * further out.</p>
 *
 * <p>So every config path is asked for through here, and asking moves the old file to the new name if
 * it is still sitting there. Three rules, each of which is the interesting half:</p>
 *
 * <ul>
 * <li><b>Move, not copy.</b> Two files that both look authoritative is the next bug, and it is a
 * worse one — edits would land in whichever the mod happened to read that day.</li>
 * <li><b>Never overwrite.</b> If the new name already exists it wins outright and the old file is
 * left where it is. Whatever is current is current; a stale file from before the rename must not be
 * able to undo settings changed since.</li>
 * <li><b>Failure is silent.</b> If the move cannot happen the mod carries on with defaults, which is
 * exactly what it would have done without this class. Nothing here is worth interrupting a game
 * launch for.</li>
 * </ul>
 */
public final class ConfigFiles {

	/** The name the mod shipped under before the rename. */
	private static final String PREVIOUS = "mcbot";

	private static final String CURRENT = "descant";

	private ConfigFiles() {
	}

	/**
	 * The path to a config file, after moving any pre-rename copy of it into place.
	 *
	 * @param name the file's current name, e.g. {@code descant.properties}
	 */
	public static Path of(String name) {
		Path directory = FabricLoader.getInstance().getConfigDir();
		Path current = directory.resolve(name);
		if (name.startsWith(CURRENT)) {
			adopt(directory.resolve(PREVIOUS + name.substring(CURRENT.length())), current);
		}
		return current;
	}

	/** Moves {@code previous} to {@code current}, unless {@code current} already exists. */
	private static void adopt(Path previous, Path current) {
		if (Files.exists(current) || !Files.isReadable(previous)) {
			return;
		}
		try {
			Files.move(previous, current);
		} catch (IOException e) {
			// Deliberately swallowed — see the class comment.
		}
	}
}

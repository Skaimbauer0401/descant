package descant.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last few things the mod said in chat.
 *
 * <p>Exists because each AI run starts a fresh conversation, so anything the model said last time is
 * gone by the next one. That is wrong most of the time it matters: a model that ends with "shall I
 * smelt them too?" and then cannot remember asking makes "yes" unanswerable. Handing it the tail of
 * the chat log at the start of a run gives the follow-up something to refer to.</p>
 *
 * <p>Not the model's own transcript, note — this is what was <em>shown to the player</em>, which is
 * the same thing the player is looking at when they type the follow-up. Replaying the model's private
 * history instead would restore a conversation the player never saw and cannot correct.</p>
 *
 * <p>Written from the client thread and read from the agent's worker thread, hence the
 * synchronisation. It is a handful of strings; there is nothing here worth being clever about.</p>
 */
public final class Transcript {

	/** How many lines are held. Comfortably more than any recall setting is allowed to ask for. */
	private static final int KEPT = 40;

	/**
	 * How much of one line is kept.
	 *
	 * <p>An inventory listing runs long, and several of them would crowd out the goal itself. The tail
	 * of such a line is the least interesting part of it.</p>
	 */
	private static final int LINE_LIMIT = 300;

	/**
	 * How old a line may be and still count as "just before this".
	 *
	 * <p>Without a limit, the first request of the evening would arrive with this morning's suggestion
	 * attached and presented as context — which is not merely useless but actively misleading, since
	 * the model has no way to tell that the thing it is being reminded of has long since stopped
	 * mattering.</p>
	 */
	private static final long FRESH_MILLIS = 10 * 60 * 1000L;

	private static final Deque<Line> lines = new ArrayDeque<>();

	private record Line(String text, long at) {
	}

	private Transcript() {
	}

	/** Notes something the mod has just said. */
	public static synchronized void record(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		String trimmed = text.strip();
		if (trimmed.length() > LINE_LIMIT) {
			trimmed = trimmed.substring(0, LINE_LIMIT) + "…";
		}
		lines.addLast(new Line(trimmed, System.currentTimeMillis()));
		while (lines.size() > KEPT) {
			lines.removeFirst();
		}
	}

	/**
	 * The most recent lines, oldest first, skipping anything too old to be context.
	 *
	 * @param count how many to return at most; zero or less returns nothing
	 */
	public static synchronized List<String> recent(int count) {
		if (count <= 0) {
			return List.of();
		}
		long cutoff = System.currentTimeMillis() - FRESH_MILLIS;
		List<String> recent = new ArrayList<>();
		for (Line line : lines) {
			if (line.at() >= cutoff) {
				recent.add(line.text());
			}
		}
		return recent.size() <= count
				? recent
				: List.copyOf(recent.subList(recent.size() - count, recent.size()));
	}
}

package mcbot.client.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import mcbot.client.BotSettings;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.BotApi;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Lets a language model drive the bot.
 *
 * <p>The model is handed the action menu and a goal in plain words, and answers with calls. Those
 * calls are run through {@link BotApi} — the same door the chat commands use — and what happened is
 * reported back so it can decide what to do next. It never touches the controls, and it cannot reach
 * anything that is not an action, so the worst a confused model can do is send the bot somewhere
 * silly.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The conversation runs on one background thread, because every request blocks for seconds and
 * doing that on the client thread would freeze the game. Actions still have to run on the client
 * thread, so each call is handed over with {@link Minecraft#submit} and waited on. All the shared
 * state between the two is {@code volatile} booleans.</p>
 *
 * <h2>Calls run to completion</h2>
 *
 * <p>{@code goto} returns the instant the route is accepted, long before the bot arrives. Reporting
 * that immediately would leave the model to poll {@code status} until something changed — which a
 * small model does badly, either giving up early or filling its context with a hundred identical
 * replies. So a call that starts the bot moving is <em>waited out</em> here, and the model is told
 * where the bot actually ended up. One call, one finished task.</p>
 */
public final class AiAgent {

	/** How long to wait for the client thread to run one handed-over task. Generous; it is a tick. */
	private static final int CLIENT_HANDOFF_SECONDS = 20;

	/** Gap between "are we there yet?" checks while an action runs. */
	private static final long SETTLE_POLL_MILLIS = 250L;

	private final BotApi api;
	private final Consumer<Component> chat;
	private final LlmProvider provider;

	/** One thread, daemon, so an in-flight request can never hold the game open on exit. */
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mcbot-ai");
		thread.setDaemon(true);
		return thread;
	});

	private volatile boolean running;
	private volatile boolean cancelled;

	public AiAgent(BotApi api, Consumer<Component> chat) {
		this.api = api;
		this.provider = new OllamaProvider();

		// Everything this class says comes from the worker thread, and putting a line in chat touches
		// the GUI — which throws "RenderSystem called from wrong thread" rather than doing anything
		// useful. Wrapping the sink once, here, means no call site has to remember; the alternative was
		// a marshalling step at each say(), which works right up until someone adds the one that forgets.
		this.chat = message -> {
			try {
				Minecraft.getInstance().execute(() -> chat.accept(message));
			} catch (RejectedExecutionException e) {
				// The client is shutting down. There is no chat left to print to, and a run being
				// abandoned mid-sentence on the way out of the game is not worth reporting.
			}
		};
	}

	public boolean isRunning() {
		return running;
	}

	/** Starts a run. One at a time — a second model steering the same bot would just fight the first. */
	public ActionResult start(String goal) {
		if (goal == null || goal.isBlank()) {
			return ActionResult.failed("Tell it what to do, e.g. /mcbot ai get me some iron.");
		}
		if (running) {
			return ActionResult.failed("Already working on something. /mcbot ai stop first.");
		}
		running = true;
		cancelled = false;
		worker.submit(() -> converse(goal.trim()));
		return ActionResult.ok("Asking " + provider.describe() + ": " + goal.trim());
	}

	/**
	 * Ends the run.
	 *
	 * <p>Stops the bot as well as the conversation. Leaving the player walking somewhere a model chose,
	 * with nothing left to change its mind, is not what anyone means by stop.</p>
	 *
	 * <p>Client thread only — it runs the {@code stop} action directly rather than handing it over.
	 * The only caller is the chat command, which is already there.</p>
	 */
	public ActionResult stop() {
		if (!running) {
			return ActionResult.failed("Nothing running.");
		}
		cancelled = true;
		return api.invoke("stop", Arguments.none());
	}

	// ---------------------------------------------------------------- the loop

	private void converse(String goal) {
		Minecraft minecraft = Minecraft.getInstance();
		int maxSteps = BotSettings.AI_MAX_STEPS.get();

		try {
			LlmProvider.Session session = provider.begin(systemPrompt(), api.actions().schema());

			// The opening message carries the current state as well as the goal. A model that has to
			// spend its first call asking where it is has wasted a step, and a small one may never
			// think to ask at all.
			LlmReply reply = session.say(goal + "\n\nRight now: " + statusLine(minecraft));

			for (int step = 1; step <= maxSteps; step++) {
				if (cancelled) {
					say("Stopped.");
					return;
				}
				if (!reply.wantsToAct()) {
					say(reply.text().isBlank() ? "Finished." : reply.text());
					return;
				}
				if (!reply.text().isBlank()) {
					say("· " + reply.text());
				}

				List<LlmProvider.ToolOutcome> outcomes = new ArrayList<>();
				for (ToolCall call : reply.calls()) {
					if (cancelled) {
						break;
					}
					say("→ " + call.describe());
					outcomes.add(new LlmProvider.ToolOutcome(call, runToCompletion(minecraft, call)));
				}
				if (cancelled) {
					say("Stopped.");
					return;
				}
				reply = session.report(outcomes);
			}

			// Out of steps rather than out of ideas. The cap exists so a model that has started going
			// in circles cannot do it all afternoon.
			say("Gave up after " + maxSteps + " steps without finishing. "
					+ "Raise it with /mcbot set aiMaxSteps if the task really is that long.");

		} catch (IOException e) {
			say(e.getMessage());
		} catch (RuntimeException e) {
			say("The AI run failed: " + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : ": " + e.getMessage()));
		} finally {
			running = false;
		}
	}

	/**
	 * Runs one call and does not answer until the bot has stopped doing it.
	 *
	 * @return what happened, in the words the model will read
	 */
	private String runToCompletion(Minecraft minecraft, ToolCall call) throws IOException {
		ActionResult result = onClientThread(minecraft, () -> api.invoke(call.name(), call.toArguments()));
		if (!result.ok()) {
			// Prefixed so it is unmistakable. A model skimming its own history needs the failures to
			// stand out from the successes, or it repeats them.
			return "FAILED: " + result.message();
		}
		if (!onClientThread(minecraft, api::busy)) {
			return result.message(); // finished on the spot — a setting change, a status read
		}

		long deadline = System.nanoTime()
				+ TimeUnit.SECONDS.toNanos(BotSettings.AI_ACTION_TIMEOUT.get());
		boolean settled = false;
		while (System.nanoTime() < deadline && !cancelled) {
			try {
				Thread.sleep(SETTLE_POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the bot to finish.");
			}
			if (!onClientThread(minecraft, api::busy)) {
				settled = true;
				break;
			}
		}

		if (cancelled) {
			return "Cancelled by the player.";
		}
		String status = statusLine(minecraft);
		return settled
				? result.message() + " Finished — " + status
				: result.message() + " Still going after " + BotSettings.AI_ACTION_TIMEOUT.get()
						+ "s, so it may be a long job or it may be stuck — " + status;
	}

	private String statusLine(Minecraft minecraft) throws IOException {
		return onClientThread(minecraft, () -> api.invoke("status", Arguments.none())).message();
	}

	/**
	 * Runs something on the client thread and waits for the answer.
	 *
	 * <p>Everything the actions touch — the player, the level, the controller — belongs to that
	 * thread, so this handover is not optional.</p>
	 */
	private <T> T onClientThread(Minecraft minecraft, Supplier<T> job) throws IOException {
		try {
			return minecraft.submit(job).get(CLIENT_HANDOFF_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted.");
		} catch (Exception e) {
			throw new IOException("The game didn't answer in time — stopping the AI run.");
		}
	}

	private void say(String message) {
		chat.accept(Component.literal("[ai] " + message));
	}

	// ---------------------------------------------------------------- the prompt

	/**
	 * The standing instructions.
	 *
	 * <p>This is the part that is really being programmed. The action descriptions tell the model
	 * <em>what</em> each call does; this tells it how the world works and what a good turn looks
	 * like — and it is written short and concrete because a 12B model reading a page of prose will
	 * follow the first half of it.</p>
	 */
	private static String systemPrompt() {
		return """
				You control a Minecraft player by calling the functions you have been given. \
				You cannot do anything else: there is no keyboard, no mouse, and no function \
				beyond the ones listed. Never invent one.

				How a turn works:
				- Call one function at a time and wait for the result.
				- Each call runs to completion before you are told what happened, so never poll \
				or wait — if you asked it to travel somewhere, the result already says whether it \
				arrived.
				- A result starting with FAILED means it did not happen. Read why and do something \
				different; calling it again unchanged will fail again.
				- When the task is done, or you cannot make progress, reply in plain words with no \
				function call. That ends the run.
				- STOP AND WARN, calling nothing, if you are about to gather or dig while \
				health is under 10 or hunger is under 6. Say which it is and what you were \
				going to do. Gathering means tunnelling underground for a long time, and \
				starting it hurt or hungry is how a bot dies. Wait to be told to go ahead.

				About the world:
				- Coordinates are x (east), z (south) and y (height). Sea level is about y=63.
				- For travelling, use goto with x and z and no y. Naming a height means guessing \
				the terrain and the bot will tunnel or pillar to reach your number.
				- find and mine are BLUNT about how they reach a target. The bot takes the \
				shortest route and tunnels through whatever is in the way, including \
				digging straight down. It will not dig into lava it can see, or take a \
				killing fall, but it will bore a hole through a build, break into a cave \
				full of mobs, and stay underground a long time. There is no movement-only \
				mode for gathering. If the player might mind a hole through their base, or \
				the bot is hurt or short of food, say so before starting rather than after.
				- To gather a resource, use find with execute=true, and ALWAYS give a count \
				unless the player really did ask for all of them. One call gathers the whole \
				amount, picking up the drops as it goes — you never call it once per block. \
				Without a count it clears every one within a few hundred blocks, which on \
				anything common takes a very long time and is rarely what was wanted.
				- If the player says "some", or does not say how many, choose a sensible number \
				yourself: about a stack of a common material, 10 to 20 of an ore.
				- Blocks and mobs are named by Minecraft id: iron_ore, oak_log, cow.
				- Set a chest with chest before a long gathering job, so the bot can empty its \
				inventory and keep going instead of stopping when full.
				- status tells you where the bot is, its health and hunger, and how full it is; \
				inventory tells you exactly what it is carrying; locate gives the exact \
				coordinates of the nearest block of a kind without going there. All three \
				cost nothing, so call them rather than guessing — locate before deciding \
				whether to walk to something or place your own.
				- look describes the surroundings: coordinates, which way it faces, dimension, \
				biome, time of day, weather, light, and every creature nearby. Free, like \
				the other two.
				- place puts a block down, in front of the bot or at a spot you name; it has to \
				be carried already. mine is its opposite, breaking the block at exact \
				coordinates. Use find when you want a kind of block wherever it happens to \
				be, and mine when you mean that particular spot.
				- craft makes things from what is carried. Small recipes happen where the bot \
				stands; ones needing a 3x3 grid send it to a crafting table by itself. It \
				cannot smelt — anything needing a furnace is out of reach for now.
				- smelt runs a furnace: it finds one, loads the items, picks its own fuel, waits \
				and collects the result. About 10 seconds per item, so ask for what you need \
				rather than everything carried.
				- use right-clicks a block: levers, buttons, doors, gates, beds. It will open a \
				smithing table or an anvil but cannot put anything into one — smelting is the \
				only station work the bot can do.
				- deposit stashes things in the chest on demand. By default it banks the haul \
				and keeps the tools, food and blocks the bot works with; pass what='food', \
				what='all', or an item id, to hand over something specific instead. drop \
				throws things away for good, so prefer deposit whenever a chest is set.
				- take is the other half of deposit: it fetches things back out of the chest — 				coal for a smelt, planks for a build. What is in the chest is unknown until 				the bot gets there, so it may come back with less than asked, or nothing.""";
	}
}

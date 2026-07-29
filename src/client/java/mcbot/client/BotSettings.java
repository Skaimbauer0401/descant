package mcbot.client;

import mcbot.client.ai.AiProvider;
import mcbot.client.control.TravelMode;
import mcbot.client.inventory.ChestSource;
import mcbot.client.settings.BooleanSetting;
import mcbot.client.settings.DoubleSetting;
import mcbot.client.settings.EnumSetting;
import mcbot.client.settings.IntSetting;
import mcbot.client.settings.StringSetting;

/**
 * Everything about the bot's behaviour that can be adjusted, plus the handful of numbers that
 * cannot.
 *
 * <p>Costs are expressed in <b>game ticks</b> — the estimated time a movement takes — which keeps
 * the A* cost function and the heuristic in the same unit and makes them directly comparable.</p>
 *
 * <h2>Settings versus constants</h2>
 *
 * <p>The two halves of this file are not the same kind of thing, and the split is the point.</p>
 *
 * <p>A <b>setting</b> is a preference: how eager the bot is to jump, how full it lets its inventory
 * get, how far it looks for ore. Reasonable people want different answers, so these are live objects
 * that can be changed while the game runs, by {@code /mcbot set} or by a model.</p>
 *
 * <p>A <b>constant</b> is a fact about Minecraft: how high a player can step, how tall their eyes
 * are, the fall distance vanilla starts charging damage at. Changing one does not tune the bot, it
 * makes the bot's model of the world wrong — so those stay {@code static final} and out of reach.
 * Publishing them as settings would be offering a choice that has only one correct answer.</p>
 */
public final class BotSettings {

	private BotSettings() {
	}

	/**
	 * Forces this class to load, so every setting below has registered itself.
	 *
	 * <p>Settings register from their constructors, which run on class initialisation — and Java only
	 * initialises a class when something first touches it. Listing the settings before anything has
	 * read one would otherwise show an empty registry.</p>
	 */
	public static void load() {
		// Touching the class is the whole job; the static initialisers do the rest.
	}

	// ================================================================ settings
	// ---------------------------------------------------------------- movement costs (ticks)

	/**
	 * Time to cross one block while sprinting. Also the A* heuristic scale.
	 *
	 * <p>{@code 20 / 5.612} — twenty ticks per second divided by the sprint speed in blocks per
	 * second. These are Baritone's numbers, derived from the vanilla movement constants rather than
	 * eyeballed, which matters because the ratios between them are what the planner actually weighs:
	 * a swim priced slightly wrong is a bot that dives, and a jump priced slightly wrong is a bot
	 * that bridges a gap it could have hopped.</p>
	 */
	public static final DoubleSetting SPRINT_COST = new DoubleSetting("sprintCost", 3.564,
			"Ticks to sprint across one block. Also the scale of the A* heuristic.");

	/** Time to cross one block while walking ({@code 20 / 4.317}). */
	public static final DoubleSetting WALK_COST = new DoubleSetting("walkCost", 4.633,
			"Ticks to walk across one block.");

	/** Extra cost for gaining a block of height. Jumping is slow and interrupts sprinting. */
	public static final DoubleSetting JUMP_COST = new DoubleSetting("jumpCost", 5.0,
			"Extra ticks charged for climbing one block. Raise it to make the bot prefer flat routes.");

	/** Extra cost per block of falling. Cheap — falling is fast — but not free, to avoid churn. */
	public static final DoubleSetting FALL_COST_PER_BLOCK = new DoubleSetting("fallCostPerBlock", 1.0,
			"Extra ticks charged per block of a safe drop.");

	/** Swimming is roughly half walking speed ({@code 20 / 2.2}). */
	public static final DoubleSetting SWIM_COST = new DoubleSetting("swimCost", 9.091,
			"Ticks to swim one block. Raise it to keep the bot out of water.");

	/** Time to climb one block of ladder or vine ({@code 20 / 2.35}). */
	public static final DoubleSetting LADDER_UP_COST = new DoubleSetting("ladderUpCost", 8.511,
			"Ticks to climb one block of ladder or vine.");

	/** Time to descend one block of ladder or vine ({@code 20 / 3.0}) — gravity helps. */
	public static final DoubleSetting LADDER_DOWN_COST = new DoubleSetting("ladderDownCost", 6.667,
			"Ticks to climb down one block of ladder or vine.");

	/**
	 * Flat surcharge on any jump, whether a step up or a gap cleared.
	 *
	 * <p>Baritone's {@code jumpPenalty}, and deliberately small: a jump really does cost only a
	 * couple of ticks of lost ground speed. Its job is to break ties in favour of not jumping, not to
	 * discourage jumping — pricing it high is what makes a bot bridge across a one-block gap it could
	 * have hopped in a quarter of the time.</p>
	 */
	public static final DoubleSetting JUMP_PENALTY = new DoubleSetting("jumpPenalty", 2.0,
			"Flat tick surcharge on every jump. Breaks ties against jumping; keep it small.");

	/** Flat penalty added on top of the estimated mining time, covering aim + re-approach. */
	public static final DoubleSetting BREAK_OVERHEAD = new DoubleSetting("breakOverhead", 6.0,
			"Flat ticks added to every block break, covering aiming and stepping back into place.");

	/** Cost of placing one support block (aim, sneak, click, re-approach). */
	public static final DoubleSetting PLACE_COST = new DoubleSetting("placeCost", 20.0,
			"Ticks charged for placing one block. Raise it to make the bot dig rather than build.");

	/**
	 * Furthest a sprint-jump may land, measured in blocks along a cardinal from the launch block.
	 *
	 * <p>2 is a one-block gap (trivial), 3 a two-block gap (reliable with a running start), 4 a
	 * three-block gap (the classic edge-of-the-block jump — possible, but wants good timing, so it is
	 * priced highest and only taken when there is nothing shorter).</p>
	 */
	public static final IntSetting MAX_PARKOUR_DISTANCE = new IntSetting("maxParkourDistance", 4, 0, 4,
			"Longest jump allowed, in blocks from the launch block. 4 is a three-block gap; 0 bans jumping gaps.");

	/**
	 * Surcharge per gap cell on a jump, when the bot could bridge that gap instead.
	 *
	 * <p>Bridging is the preferred way across a gap: it is slower and spends blocks, but it leaves
	 * solid ground behind and cannot drop the bot into a ravine. A jump has no such failure floor —
	 * missing one costs the fall.</p>
	 *
	 * <p>The number is derived rather than guessed. Bridging one cell costs {@link #PLACE_COST} plus a
	 * step across it ({@link #WALK_COST}), about 24.6 ticks; a jump crosses that same cell for roughly
	 * {@code WALK_COST} of its own price, so the gap between the two options is about 20 ticks per
	 * cell. Anything above that tips the choice to bridging; this leaves a clear margin on top.</p>
	 *
	 * <p>Applied only when placing is allowed. When it is not — {@code /mcbot walk}, or having run out
	 * of blocks — there is nothing to prefer, so jumps go back to their honest price rather than
	 * becoming a reason to detour for twenty blocks.</p>
	 */
	public static final DoubleSetting PARKOUR_BRIDGE_SURCHARGE = new DoubleSetting(
			"parkourBridgeSurcharge", 22.0,
			"Ticks charged per gap cell on a jump the bot could bridge instead. "
					+ "Above about 20 it bridges; set it to 0 to always jump.");

	/**
	 * Furthest jump that may also gain a block of height.
	 *
	 * <p>An ascending jump spends part of its arc climbing, so it reaches less far. Baritone allows it
	 * up to three blocks out and always sprints it; four-block ascending jumps are not reliably
	 * possible in vanilla and are not generated.</p>
	 */
	public static final IntSetting MAX_PARKOUR_ASCEND_DISTANCE = new IntSetting(
			"maxParkourAscendDistance", 3, 0, 3,
			"Longest jump that may also gain a block of height. Four-block ascending jumps are not possible in vanilla.");

	// ---------------------------------------------------------------- pathfinding limits

	/** Hard cap on expanded nodes per search. Prevents unbounded searches in open worlds. */
	public static final IntSetting MAX_NODES = new IntSetting("maxNodes", 20_000, 100, 5_000_000,
			"Most nodes one search may expand before giving up on reaching the goal in one go.");

	/**
	 * Milliseconds of A* work allowed per client tick.
	 *
	 * <p>The search is time-sliced across ticks so a long search never stalls the render thread — 3 ms
	 * of a 50 ms tick is invisible. Raising this finds paths sooner at the cost of frame rate.</p>
	 */
	public static final DoubleSetting SEARCH_BUDGET_MILLIS = new DoubleSetting(
			"searchBudgetMillis", 3.0, 0.1, 40.0,
			"Milliseconds of pathfinding allowed per tick. A tick is 50ms, so this is taken out of the frame budget.");

	/**
	 * Cost multiplier applied to positions on the route being replaced.
	 *
	 * <p>Baritone's {@code backtrackCostFavoringCoefficient}. Two ways around an obstacle are usually
	 * near-identical in cost, so without a bias each replan is free to prefer the other one and the bot
	 * oscillates between them. Halving the cost of the incumbent route makes a replan behave like a
	 * correction to the current plan rather than an unrelated second opinion.</p>
	 */
	public static final DoubleSetting BACKTRACK_FAVOUR = new DoubleSetting("backtrackFavour", 0.5,
			0.0, 1.0,
			"How strongly a replan sticks to the route it is replacing. 1 ignores it; lower values "
					+ "stop the bot dithering between two equal ways round an obstacle.");

	/**
	 * Estimated ticks left on the current route below which the next segment starts being planned,
	 * while the bot keeps walking.
	 *
	 * <p>A partial path used to end with the bot standing still in {@code PLANNING} until the next
	 * search finished. Overlapping the search with the walk removes that stall entirely: roughly seven
	 * seconds of route is enough runway for a search to complete before it is needed.</p>
	 */
	public static final DoubleSetting PLANNING_TICK_LOOKAHEAD = new DoubleSetting(
			"planningTickLookahead", 150.0,
			"Ticks of route left when the next segment starts being planned, so the bot never stands still waiting.");

	/** Vertical scan limit when looking for ground below a candidate step. */
	public static final IntSetting MAX_FALL_SCAN = new IntSetting("maxFallScan", 20,
			"How far down to look for ground beneath a candidate step.");

	// ---------------------------------------------------------------- survival

	/** How closely the player must sit to the middle of a column before pillaring upward. */
	public static final DoubleSetting PILLAR_CENTRE_TOLERANCE = new DoubleSetting(
			"pillarCentreTolerance", 0.18,
			"How close to the middle of a block the bot must stand before placing one under itself.");

	/** Air supply (of 300) below which surfacing takes priority over everything else. */
	public static final IntSetting AIR_CRITICAL = new IntSetting("airCritical", 120, 0, 300,
			"Air supply, out of 300, below which surfacing beats everything else.");

	/** Node budget for the search that finds the nearest breathable space. */
	public static final IntSetting AIR_SEARCH_NODES = new IntSetting("airSearchNodes", 800,
			"Node budget for the search that finds the nearest breathable space.");

	/** Blocks the bot may mine through in a single move (e.g. head + feet of a wall). */
	public static final IntSetting MAX_BREAK_PER_MOVE = new IntSetting("maxBreakPerMove", 2, 0, 6,
			"Blocks the bot may mine through in one movement. 0 stops it tunnelling at all.");

	// ---------------------------------------------------------------- locating

	/** How many places {@code locate} reports when it is not told a number. */
	public static final IntSetting LOCATE_COUNT = new IntSetting("locateCount", 5, 1, 30,
			"How many places 'locate' reports by default.");

	/**
	 * Minimum gap between the places {@code locate} reports.
	 *
	 * <p>Ore comes in veins. Without a gap, "the five nearest iron_ore" is five blocks of the same
	 * vein — a correct answer to the question asked and no use at all for deciding where to go. Four
	 * blocks is enough to separate veins while still listing two furnaces side by side as two.</p>
	 */
	public static final IntSetting LOCATE_SPACING = new IntSetting("locateSpacing", 4, 0, 64,
			"How far apart the places 'locate' reports must be, so a vein counts once. 0 lists "
					+ "neighbouring blocks separately.");

	// ---------------------------------------------------------------- travelling

	/**
	 * How much of the world a journey may rearrange, when the caller does not say.
	 *
	 * <p>{@code try_walk} rather than {@code build}, because the destructive version should be the
	 * one you ask for. Most trips are walkable and the ones that are not still escalate, so the
	 * default costs a wasted search on a buried target and saves a tunnel through everything else.</p>
	 */
	public static final EnumSetting<TravelMode> TRAVEL_MODE = new EnumSetting<>(
			"travelMode", TravelMode.TRY_WALK,
			"How the bot gets somewhere when a command does not say: walk only, build from the start, "
					+ "or try walking and only dig if there is no way on foot.");

	// ---------------------------------------------------------------- scaffolding

	/** The {@link #SCAFFOLD_BLOCK} value meaning "no preference — spend whatever is spare". */
	public static final String ANY_SCAFFOLD = "any";

	/**
	 * Which block the bot spends on the scaffolding it places to get somewhere.
	 *
	 * <p>The pathfinder places blocks of its own accord — bridging gaps, pillaring upward — and this
	 * decides what it spends. Left at {@code any} it grabs the first solid block that comes to hand,
	 * which is fine when the pack is full of cobble and awful when the only stack left is the
	 * player's diamond blocks.</p>
	 *
	 * <p>A named block is <b>strict, not preferred</b>. Running out stops the bridging and the bot
	 * routes around instead of quietly falling back to something valuable — the whole reason for
	 * naming one is to bound what may be spent.</p>
	 */
	public static final StringSetting SCAFFOLD_BLOCK = new StringSetting(
			"scaffoldBlock", ANY_SCAFFOLD,
			"a block id such as 'cobblestone' or 'dirt', or 'any'",
			"Which block to spend on the bridges and pillars the bot builds to get somewhere. "
					+ "'any' spends whatever solid block is spare, which may include something valuable. "
					+ "A named block is never substituted: when it runs out the bot stops building and "
					+ "routes around instead.");

	// ---------------------------------------------------------------- display

	/** Whether the planned route is drawn in the world. */
	public static final BooleanSetting SHOW_PATH = new BooleanSetting("showPath", true,
			"Whether the planned route is drawn in the world.");

	// ---------------------------------------------------------------- the model
	//
	// Local and cloud are the same setting shape on purpose: an Ollama cloud model is served through
	// the same local daemon, so the only difference that reaches the code is the model name. Keeping
	// both names side by side and switching with one flag makes them easy to compare on one task,
	// which is the whole reason to have both.

	/**
	 * The local model.
	 *
	 * <p>It has to support tool calling — Ollama will refuse outright otherwise, which rules out
	 * several popular small models including the Gemma family.</p>
	 */
	public static final StringSetting AI_MODEL = new StringSetting("aiModel", "gemma4:12b",
			"an installed, tool-capable Ollama model",
			"The local model that drives the bot. Must support tool calling. gemma4:12b picks actions "
					+ "correctly and runs entirely on this machine; qwen3:14b is stronger if you have "
					+ "the memory for it.");

	/**
	 * The cloud model.
	 *
	 * <p>MiniMax M3, chosen by measurement rather than reputation. Asked to gather iron and bank it in
	 * a chest, it was the only free model that both picked the right actions <em>and</em> got their
	 * order right, in about two seconds. {@code gpt-oss:120b} confused {@code set} with {@code chest};
	 * {@code nemotron-3-ultra} was right but took nearly a minute per turn, which across a dozen turns
	 * is a very long time to watch nothing happen.</p>
	 *
	 * <p>Cloud models are retired often — several were withdrawn within the last month — so expect to
	 * change this. Needs {@code ollama signin} once.</p>
	 */
	public static final StringSetting AI_CLOUD_MODEL = new StringSetting(
			"aiCloudModel", "minimax-m3:cloud",
			"an Ollama cloud model name",
			"The cloud model, used when aiProvider is 'cloud'. Needs 'ollama signin' once. Stronger than "
					+ "anything local, at the cost of sending the conversation off this machine. "
					+ "nemotron-3-ultra:cloud is heavier but far slower.");

	/**
	 * Claude's model id.
	 *
	 * <p>Haiku by default. Choosing between twenty functions and getting their order right is a task
	 * the small model is good at, and the difference in cost between it and Sonnet is far larger than
	 * the difference in how well the bot behaves.</p>
	 */
	public static final StringSetting AI_CLAUDE_MODEL = new StringSetting(
			"aiClaudeModel", "claude-haiku-4-5-20251001",
			"an Anthropic model id",
			"Which Claude model to use when aiProvider is 'claude'. Haiku is the cheap, fast one and "
					+ "is well suited to picking actions; claude-sonnet-4-5 is stronger and dearer.");

	/**
	 * Gemini's model id.
	 *
	 * <p>Flash rather than Flash-Lite. The bot's characteristic failure is picking the wrong action out
	 * of twenty, not writing a poor sentence, and Flash is the tier Google puts forward for agentic
	 * work; at a handful of calls per task the saving from dropping a tier is not worth the mistakes.
	 * Deliberation is charged against the reply budget on these models, which is the other reason not
	 * to pick the one that thinks hardest.</p>
	 */
	public static final StringSetting AI_GEMINI_MODEL = new StringSetting(
			"aiGeminiModel", "gemini-3.6-flash",
			"a Gemini model id",
			"Which Gemini model to use when aiProvider is 'gemini'. gemini-3.6-flash is the balanced one "
					+ "and fits in the free tier; gemini-3.5-flash-lite is faster and cheaper still.");

	/**
	 * Where the model comes from.
	 *
	 * <p>Defaults to {@code local}: the one that costs nothing, needs no account and sends nothing
	 * anywhere. A default that quietly bills someone would be the wrong way round, however cheap.</p>
	 */
	public static final EnumSetting<AiProvider> AI_PROVIDER = new EnumSetting<>(
			"aiProvider", AiProvider.LOCAL,
			"Which model drives the bot: a local Ollama model, an Ollama cloud model, Anthropic's API or "
					+ "Google's. 'claude' needs an ANTHROPIC_API_KEY and is billed per token — a Claude "
					+ "Pro subscription does not cover API use. 'gemini' needs a GEMINI_API_KEY and has a "
					+ "free tier.");

	/** Where the Ollama daemon is. Cloud models go through it too. */
	public static final StringSetting AI_HOST = new StringSetting(
			"aiHost", "http://localhost:11434", "a base URL",
			"Where Ollama is listening. Cloud models are proxied through this same daemon.");

	/**
	 * How many rounds of thinking the model gets before the run is called off.
	 *
	 * <p>A safety net rather than a budget. A model that has started going in circles will do it
	 * indefinitely, and this is what stops it doing so all afternoon.</p>
	 */
	public static final IntSetting AI_MAX_STEPS = new IntSetting("aiMaxSteps", 12, 1, 200,
			"How many rounds of function calls one AI run may take before it is called off.");

	/** How long to wait for the model to reply. Cloud round-trips on a big model are not quick. */
	public static final IntSetting AI_REQUEST_TIMEOUT = new IntSetting(
			"aiRequestTimeout", 180, 5, 900,
			"Seconds to wait for the model to answer before giving up on the request.");

	/**
	 * How long one action may run before the model is told about it anyway.
	 *
	 * <p>Not a cancellation — the bot keeps going. It is the point at which waiting silently stops
	 * being useful and the model is better off knowing the job is long, or stuck.</p>
	 */
	public static final IntSetting AI_ACTION_TIMEOUT = new IntSetting(
			"aiActionTimeout", 300, 5, 3600,
			"Seconds to let one action run before reporting back to the model. The bot carries on "
					+ "either way; this only decides when the model hears about it.");

	/**
	 * Model temperature.
	 *
	 * <p>Low. Picking the right function from a list is not a task that benefits from invention, and
	 * a small model at default heat will cheerfully call something that does not exist.</p>
	 */
	public static final DoubleSetting AI_TEMPERATURE = new DoubleSetting("aiTemperature", 0.2,
			0.0, 2.0,
			"How inventive the model is. Low is right for choosing between functions.");

	// ---------------------------------------------------------------- banking the haul

	/**
	 * Where {@code /mcbot chest} looks when it is not told.
	 *
	 * <p>Pointing is the better default. Nearest is the quicker of the two to use, but it is also the
	 * one that can silently pick the wrong container, and a mining run that banks two hours of ore in
	 * next door's chest is a worse outcome than one that asks you to look at the right one.</p>
	 */
	public static final EnumSetting<ChestSource> CHEST_SOURCE = new EnumSetting<>(
			"chestSource", ChestSource.LOOKING_AT,
			"How 'chest' picks a container when no source is given.");

	/** How far the looking-at raycast reaches. Well past vanilla's interaction range, on purpose. */
	public static final DoubleSetting CHEST_LOOK_RANGE = new DoubleSetting("chestLookRange", 32.0,
			1.0, 128.0,
			"How far away a container can be and still be picked by pointing at it.");

	/** How far around the player the nearest-container search looks. */
	public static final IntSetting CHEST_SEARCH_RADIUS = new IntSetting("chestSearchRadius", 16, 1, 128,
			"How far around the player to search when picking the nearest container.");

	/**
	 * Fraction of the inventory that must be occupied before the bot breaks off to bank the haul.
	 *
	 * <p>Not 1.0 deliberately. Waiting for the very last slot means the trip only starts once drops are
	 * already being left on the ground, and a stack that partially merges can fill the remainder in a
	 * single block break. Leaving a few slots spare covers the walk back to the chest.</p>
	 */
	public static final DoubleSetting DEPOSIT_FULLNESS = new DoubleSetting("depositFullness", 0.85,
			0.1, 1.0,
			"How full the inventory gets before the bot goes to bank the haul. Below 1 so it sets off "
					+ "before drops start being left on the ground.");

	// ---------------------------------------------------------------- searching and collecting

	/** Radius searched for dropped items when the bot runs out of something it needs. */
	public static final DoubleSetting ITEM_SEARCH_RADIUS = new DoubleSetting("itemSearchRadius", 50.0,
			"How far to look for a dropped item the bot has run out of.");

	/**
	 * How far from the kill or the broken block loot is looked for.
	 *
	 * <p>Measured from that spot, not from the player, and kept tight: drops scatter a block or two
	 * and no further. A generous radius centred on the player turns a loot sweep into hoovering up
	 * everything in the area, which is not what was asked for.</p>
	 */
	public static final DoubleSetting COLLECT_RADIUS = new DoubleSetting("collectRadius", 5.0,
			"How far from a kill or a broken block to sweep up drops. Kept tight so it collects its own loot, not the area's.");

	/**
	 * Ticks to keep looking before deciding nothing dropped. Drops do not appear on the same tick
	 * the block breaks or the mob dies, so an immediate "nothing here" would always be wrong.
	 */
	public static final IntSetting COLLECT_GRACE_TICKS = new IntSetting("collectGraceTicks", 12,
			"Ticks to wait for drops to appear before deciding there were none.");

	/** Ticks before giving up on loot that cannot be reached, so a hunt is never stalled by it. */
	public static final IntSetting COLLECT_TIMEOUT_TICKS = new IntSetting("collectTimeoutTicks", 140,
			"Ticks before abandoning loot that cannot be reached.");

	/**
	 * Radius searched when hunting for a block type.
	 *
	 * <p>Bounded in practice by what the client has loaded, so raising it past the render distance
	 * costs nothing and finds nothing extra. It is affordable because the search rejects whole 16³
	 * sections on a palette check and stops expanding once a match is found — a common block exits
	 * on the first ring, and a rare one skips almost every section without reading a block.</p>
	 */
	public static final IntSetting BLOCK_SEARCH_RADIUS = new IntSetting("blockSearchRadius", 320,
			1, 2048,
			"How far to search for a block by name. Bounded in practice by the loaded chunks.");

	/** Radius searched when hunting for a mob. Entities only exist client-side when nearby. */
	public static final DoubleSetting ENTITY_SEARCH_RADIUS = new DoubleSetting(
			"entitySearchRadius", 192.0,
			"How far to search for a mob by name. Mobs only exist client-side when nearby.");

	/**
	 * Squared distance a pursued mob may drift from the current goal before the route is re-aimed.
	 * Too small and the bot replans every tick as the mob shuffles; too large and it walks to where
	 * the mob used to be.
	 */
	public static final DoubleSetting RETARGET_DISTANCE_SQR = new DoubleSetting(
			"retargetDistanceSqr", 9.0,
			"Squared distance a hunted mob may wander before the route is re-aimed at it.");

	// ---------------------------------------------------------------- execution

	/**
	 * Distance at which a path node counts as entered, so progress moves to the following node.
	 * Generous enough that a sprinting player never skims past a node without registering it.
	 */
	public static final DoubleSetting NODE_ARRIVAL_DISTANCE = new DoubleSetting(
			"nodeArrivalDistance", 0.6,
			"How close counts as having reached a path node.");

	/**
	 * Degrees per tick the <em>walk</em> heading may turn. Movement direction follows the yaw, so
	 * turning it gradually keeps the bot from overshooting corners and walking off edges. Only the
	 * navigation steering is eased like this; aiming at a block or mob still snaps instantly.
	 */
	public static final DoubleSetting NAV_TURN_PER_TICK = new DoubleSetting("navTurnPerTick", 22.0,
			0.0, 180.0,
			"Degrees per tick the walking heading may turn. Low is smooth but cuts corners wide; high snaps round.");

	/** Only sprint when heading this close (degrees) to the desired direction. */
	public static final DoubleSetting SPRINT_ANGLE_THRESHOLD = new DoubleSetting(
			"sprintAngleThreshold", 35.0, 0.0, 180.0,
			"How close the heading must be to the intended direction, in degrees, before the bot sprints.");

	/**
	 * Beyond this heading error the bot turns on the spot instead of walking. Holding forward while
	 * facing the wrong way just walks away from the path and swings back — the "orbiting" look.
	 */
	public static final DoubleSetting MAX_FORWARD_ANGLE = new DoubleSetting("maxForwardAngle", 90.0,
			0.0, 180.0,
			"Heading error, in degrees, past which the bot turns on the spot rather than walking.");

	/**
	 * Sprinting needs somewhere to sprint to. Below this many nodes remaining the bot walks, which
	 * stops it charging past the final node and having to double back.
	 */
	public static final IntSetting MIN_STEPS_FOR_SPRINT = new IntSetting("minStepsForSprint", 3,
			"Nodes that must remain ahead before the bot will sprint.");

	/**
	 * Distance from the route at which the plan is abandoned outright and replanned.
	 *
	 * <p>Measured to <em>either end</em> of the movement being executed, so being partway along it is
	 * never mistaken for having wandered off.</p>
	 */
	public static final DoubleSetting PATH_ABANDON_DISTANCE = new DoubleSetting(
			"pathAbandonDistance", 3.0,
			"How far off the route the bot may get before the plan is thrown away.");

	/**
	 * Distance from the route that counts as drifting rather than leaving it.
	 *
	 * <p>Between this and {@link #PATH_ABANDON_DISTANCE} the bot is given {@link #MAX_TICKS_OFF_PATH}
	 * ticks to recover before the plan is thrown away. Baritone tolerates drift like this rather than
	 * replanning on the first tick of it, because being shoved a block sideways by a mob or clipping a
	 * corner is normal and self-correcting — replanning instantly turns a wobble into a stutter, and
	 * a stutter next to a wall into a bot that never gets anywhere.</p>
	 */
	public static final DoubleSetting PATH_DRIFT_DISTANCE = new DoubleSetting("pathDriftDistance", 2.0,
			"How far off the route counts as drifting rather than having left it.");

	/**
	 * Ticks the bot may spend drifting off the route before the plan is abandoned.
	 *
	 * <p>Baritone's value is 200. That is far too patient here: Baritone catches a failing movement
	 * separately through its per-movement state machines, so the drift counter is a last resort, while
	 * for us it <em>is</em> the detector. At 200 the bot ground against a corner for ten seconds before
	 * trying anything else.</p>
	 */
	public static final IntSetting MAX_TICKS_OFF_PATH = new IntSetting("maxTicksOffPath", 20,
			"Ticks the bot may drift off the route before the plan is thrown away.");

	// ---------------------------------------------------------------- parkour

	/**
	 * How many nodes ahead a coming jump forces sprinting on. A sprint-jump lives or dies on the
	 * run-up: arriving at the launch block already at full speed is the difference between clearing a
	 * three-block gap and dropping into it. So as soon as a jump appears this close ahead, the
	 * approach sprints regardless of the usual "somewhere to sprint to" gate.
	 */
	public static final IntSetting PARKOUR_RUNWAY_STEPS = new IntSetting("parkourRunwaySteps", 4,
			"How many nodes before a jump the bot starts its run-up.");

	/**
	 * Distance (blocks) at or above which a jump is taken at a full sprint; everything shorter is a
	 * <em>walking</em> jump.
	 *
	 * <p>The single most important number in parkour, and the one that was wrong. A sprint-jump
	 * carries roughly four blocks, so sprinting a two-block move (a one-block gap) sails clean over
	 * the landing and drops into the next hole — no landing brake can rescue a jump that never touched
	 * the block. Vanilla needs the sprint only for the three-block gap, which is distance 4 here, and
	 * that is exactly where Baritone draws the line: it prices distance 2 and 3 at the walking rate
	 * and only distance 4 at the sprinting rate.</p>
	 */
	public static final IntSetting PARKOUR_SPRINT_MIN_DISTANCE = new IntSetting(
			"parkourSprintMinDistance", 4, 2, 4,
			"Shortest jump taken at a full sprint. Below this the bot jumps at a walk — sprinting a "
					+ "short gap overshoots the landing.");

	// ---------------------------------------------------------------- route tracking

	/**
	 * How many nodes ahead progress tracking will look when deciding how far along the route the
	 * player has got. Bounds the per-tick cost, and lets a fall or a shove that skipped several nodes
	 * be recognised instead of reading as being lost.
	 */
	public static final IntSetting MAX_LOOKAHEAD_STEPS = new IntSetting("maxLookaheadSteps", 8,
			"How many nodes ahead to look when working out how far along the route the bot has got.");

	/** Spacing between clearance samples along a candidate straight line. */
	public static final DoubleSetting LINE_SAMPLE_SPACING = new DoubleSetting("lineSampleSpacing", 0.35,
			0.05, 1.0,
			"Spacing of the clearance samples when testing whether a straight line is walkable.");

	/**
	 * Grace period, in ticks, allowed to a movement <em>on top of what the planner estimated it would
	 * take</em>, before the bot decides it is stuck and replans.
	 *
	 * <p>Baritone budgets each movement against its own cost estimate rather than against one global
	 * timeout, and that distinction matters: a one-block walk and a tunnel through obsidian are not
	 * the same kind of slow. A flat timeout has to be set long enough for the worst legitimate case,
	 * which makes it far too patient with a bot wedged against a fence — the common failure. Adding
	 * the grace to the estimate keeps quick moves on a short leash while giving genuinely slow ones
	 * all the time they need.</p>
	 *
	 * <p>Progress is measured as movement <em>along the route</em>, not as movement in general. A bot
	 * circling on the spot or shuffling against a wall is moving the whole time, so a position-based
	 * test would never fire.</p>
	 */
	public static final IntSetting MOVEMENT_TIMEOUT_TICKS = new IntSetting("movementTimeoutTicks", 40,
			"Ticks a movement may overrun its own estimate before the bot decides it is stuck.");

	/** Consecutive failed searches before the bot gives up entirely. */
	public static final IntSetting MAX_REPLAN_FAILURES = new IntSetting("maxReplanFailures", 4,
			"Failed searches in a row before the bot gives up.");

	/**
	 * Ticks to wait for the player to land before planning anyway. A jump lands well inside this;
	 * the cap only matters for a genuine long fall, where planning snaps to the ground below.
	 */
	public static final IntSetting AIRBORNE_PLAN_WAIT = new IntSetting("airbornePlanWait", 15,
			"Ticks to wait for the bot to land before planning from mid-air anyway.");

	/**
	 * How many times the bot may replan after getting stuck without ever getting meaningfully
	 * closer to the goal, before it concludes the goal is unreachable and stops.
	 */
	public static final IntSetting MAX_STUCK_REPLANS = new IntSetting("maxStuckReplans", 5,
			"Replans without real progress before the bot concludes the goal is unreachable.");

	/**
	 * Reduction in the goal's own remaining-cost estimate that counts as "this replan actually
	 * helped". In ticks — roughly two blocks of sprinting.
	 */
	public static final DoubleSetting STUCK_PROGRESS_MARGIN = new DoubleSetting(
			"stuckProgressMargin", 7.0,
			"Improvement in ticks that counts as a replan having actually helped.");

	/**
	 * Squared horizontal distance below which a steering target is treated as directly above or
	 * below us. Heading is meaningless at that range and the computed yaw is pure noise, which
	 * makes the player spin on the spot.
	 */
	public static final DoubleSetting YAW_DEADZONE_SQR = new DoubleSetting("yawDeadzoneSqr", 0.04,
			"Squared horizontal distance below which a target counts as straight up or down, so the bot does not spin.");

	/** How close (blocks) the bot must get to the goal to declare success. */
	public static final DoubleSetting GOAL_TOLERANCE = new DoubleSetting("goalTolerance", 0.8,
			"How close the bot must get to the goal to call it arrived.");

	/**
	 * Vertical slack on arrival. One block, not one and a half: at 1.5 the bot counted standing on
	 * top of the goal block as having arrived, which for ore hunting meant never actually mining it.
	 */
	public static final DoubleSetting GOAL_VERTICAL_TOLERANCE = new DoubleSetting(
			"goalVerticalTolerance", 1.0,
			"Vertical slack on arrival. Above one block the bot counts standing on top of its target as reaching it.");

	// ---------------------------------------------------------------- combat and eating
	//
	// Not Baritone's territory — it has no combat or hunger handling at all. Kept as a deliberate
	// superset, because a navigation bot that starves or gets beaten to death mid-route does not
	// finish the route.

	/** Interaction reach. Vanilla survival is 4.5; stay under it for block break/place. */
	public static final DoubleSetting REACH = new DoubleSetting("reach", 4.0, 1.0, 4.5,
			"How far the bot will reach to break or place a block. Vanilla's limit is 4.5.");

	/**
	 * Eat when hunger is at or below this. Sprinting needs more than 6 hunger points, so topping up
	 * well before that keeps the bot at sprint speed rather than trudging.
	 */
	public static final IntSetting EAT_BELOW_FOOD_LEVEL = new IntSetting("eatBelowFoodLevel", 14,
			0, 20,
			"Hunger, out of 20, at or below which the bot eats. Sprinting needs more than 6, so eat well before that.");

	/** Below this health an emergency food (golden apple) is worth spending. */
	public static final DoubleSetting EMERGENCY_HEAL_HEALTH = new DoubleSetting(
			"emergencyHealHealth", 6.0, 0.0, 20.0,
			"Health, out of 20, below which a golden apple is worth spending.");

	/** Missing health that justifies eating purely to enable regeneration. */
	public static final DoubleSetting HEAL_BY_EATING_THRESHOLD = new DoubleSetting(
			"healByEatingThreshold", 4.0, 0.0, 20.0,
			"Missing health that justifies eating purely to start natural regeneration.");

	/** Attack-strength fraction to wait for. Swinging early does a fraction of the damage. */
	public static final DoubleSetting ATTACK_STRENGTH_THRESHOLD = new DoubleSetting(
			"attackStrengthThreshold", 0.9, 0.0, 1.0,
			"How far the attack cooldown must have recharged before swinging. Swinging early does a fraction of the damage.");

	/**
	 * Distance at which a hostile is dealt with. Deliberately close to vanilla's 3-block attack
	 * reach: the bot defends itself from what reaches it, rather than chasing everything it sees
	 * and never finishing the journey.
	 */
	public static final DoubleSetting COMBAT_ENGAGE_RANGE = new DoubleSetting(
			"combatEngageRange", 3.5, 0.0, 16.0,
			"How close a hostile must get before the bot fights back. Near vanilla's 3-block reach, so "
					+ "it defends itself rather than chasing everything it sees.");

	/** Keep this far from a swelling creeper. Trading hits with one is never worth it. */
	public static final DoubleSetting CREEPER_DANGER_RANGE = new DoubleSetting(
			"creeperDangerRange", 6.0, 0.0, 32.0,
			"How far the bot backs off from a swelling creeper.");

	// ================================================================ constants
	//
	// Facts about Minecraft, not preferences. Changing one does not tune the bot — it makes the
	// bot's model of the world disagree with the game, which shows up as movements the physics
	// refuses to perform rather than as different behaviour.

	/** Diagonal movement covers sqrt(2) blocks. Geometry, not a choice. */
	public static final double DIAGONAL_MULTIPLIER = Math.sqrt(2.0);

	/**
	 * Height a player can walk up without jumping. Above this a move needs a jump; above
	 * {@link #MAX_JUMP_HEIGHT} it is not possible at all.
	 */
	public static final double STEP_HEIGHT = 0.6;

	/**
	 * The highest a player can jump, unaided.
	 *
	 * <p>Checked against the <em>real</em> surface heights rather than block coordinates, because
	 * partial blocks make those disagree. Stepping onto a snow-covered block looks like an ordinary
	 * one-block hop on the grid, but the snow adds up to 0.875 on top — a 1.875 climb that no jump
	 * will ever complete, so the bot would wedge against it retrying.</p>
	 */
	public static final double MAX_JUMP_HEIGHT = 1.25;

	/**
	 * Falls deeper than this need water at the bottom or a bucket in hand.
	 *
	 * <p>Vanilla fall damage is {@code floor(distance) - 3}, so three blocks is the exact boundary
	 * of a free drop.</p>
	 */
	public static final int SAFE_FALL_DISTANCE = 3;

	/**
	 * Eye height of a standing player, for working out the view point from a <em>hypothetical</em>
	 * stance the bot is not occupying yet (reach and line-of-sight tests when picking where to
	 * stand). {@code player.getEyeHeight()} only answers for where the player actually is.
	 */
	public static final double STANDING_EYE_HEIGHT = 1.62;

	/**
	 * Half the player's collision width (0.6 wide), plus a margin. A straight-line walkability test
	 * checks this far to each side of the line, so it cannot approve a route that clips a corner the
	 * body would actually hit.
	 */
	public static final double PLAYER_HALF_WIDTH = 0.32;

	/**
	 * How far above the target block the player's feet must rise before a pillar block can be
	 * placed. A block cannot be placed into the space the player occupies, so the bot jumps and
	 * places underneath itself — this is the clearance that makes the space legal to fill.
	 */
	public static final double PILLAR_CLEARANCE = 1.0;

	/**
	 * A collision shape must reach at least this high within its block to count as standable
	 * ground. Full blocks (1.0) and dirt paths (0.9375) qualify; slabs (0.5) deliberately do not,
	 * because the block-grid model assumes the player's feet sit on a block boundary.
	 */
	public static final double MIN_GROUND_HEIGHT = 0.9;

	/**
	 * Lowest collision surface that still counts as something to stand on <em>inside</em> its own
	 * cell — see {@link mcbot.client.path.WorldView#isHalfSupport}.
	 *
	 * <p>Set just under a slab's 0.5 so bottom slabs qualify. Anything shallower (a carpet, a pressure
	 * plate, a lily pad) is walked over rather than stood on, and modelling it as a stance would only
	 * add nodes that behave identically to the ground beneath them.</p>
	 */
	public static final double MIN_HALF_GROUND_HEIGHT = 0.45;

	/** Natural regeneration only runs at or above this hunger level. A vanilla rule. */
	public static final int REGEN_FOOD_LEVEL = 18;

	// ================================================================ derived

	/**
	 * The per-tick search budget in nanoseconds, which is what the timing code deals in.
	 *
	 * <p>Exposed as milliseconds above because that is the unit the number means something in — 3 of
	 * a tick's 50 — while nanoseconds are only convenient for {@code System.nanoTime}.</p>
	 */
	public static long searchBudgetNanos() {
		return (long) (SEARCH_BUDGET_MILLIS.get() * 1_000_000.0);
	}
}

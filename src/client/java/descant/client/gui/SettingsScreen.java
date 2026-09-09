package descant.client.gui;

import java.util.List;

import descant.client.BotSettings;
import descant.client.ai.AiProvider;
import descant.client.ai.ApiKeys;
import descant.client.settings.SettingRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen: everything {@code /descant set} can change, with somewhere to click instead.
 *
 * <p>Two tabs, because the settings are two different kinds of thing. <b>Simple</b> is what the bot
 * should do — which model drives it, whether it may mine through a base, where it banks the haul —
 * and a wrong answer there is a preference you disagree with. <b>Advanced</b> is the tuning: the
 * numbers inside the A* cost function and the movement executor, where a wrong answer is a bot that
 * walks into walls and the only way to pick a right one is to know why the current one is what it is.
 * Putting {@code sprintCost} on the first page somebody opens is how a working bot gets broken by
 * curiosity.</p>
 *
 * <p>Two things on the simple tab exist because of specific afternoons lost to them, both about the
 * model. There is <b>one model row, pointed at whichever provider is chosen</b>, so the six model
 * settings cannot be confused for one another. And the line above the buttons says which model will
 * actually be asked and whether its key was found — a question the chat commands could only answer if
 * you already knew to ask it.</p>
 *
 * <p>Deliberately not a pause screen. The bot keeps walking, mining and thinking while this is open,
 * which is what makes it possible to nudge a cost and watch the route change rather than guessing.</p>
 */
public final class SettingsScreen extends Screen {

	/** Title, tabs, and the search box on the tab that has one. */
	private static final int HEADER = 74;
	/** The count line, the status line, then the buttons. */
	private static final int FOOTER = 60;

	private static final int BUTTON_WIDTH = 150;
	private static final int TAB_WIDTH = 100;
	private static final int SEARCH_WIDTH = 300;

	private static final int DIM = 0xFFA0A0A0;
	private static final int WARN = 0xFFFF5555;

	/** Where {@code Done} goes back to — normally nothing, but the pause menu if opened from there. */
	private final Screen parent;

	/**
	 * Both survive the rebuild a window resize causes, so resizing does not silently drop a search or
	 * throw somebody back onto a tab they had left.
	 */
	private boolean advanced;
	private String query = "";

	private SettingsList list;
	private Button simpleTab;
	private Button advancedTab;
	private EditBox search;
	private Button resetAll;
	private boolean resetArmed;
	private int showing;

	public SettingsScreen(Screen parent) {
		super(Component.literal("descant settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		simpleTab = addRenderableWidget(Button.builder(Component.literal("Simple"), button -> show(false))
				.bounds(width / 2 - TAB_WIDTH - 2, 26, TAB_WIDTH, 20)
				.build());
		advancedTab = addRenderableWidget(
				Button.builder(Component.literal("Advanced"), button -> show(true))
						.bounds(width / 2 + 2, 26, TAB_WIDTH, 20)
						.build());
		advancedTab.setTooltip(Tooltip.create(Component.literal(
				"Every setting there is, including the ones that will break the bot if guessed at.")));

		search = addRenderableWidget(new EditBox(font, (width - SEARCH_WIDTH) / 2, 50, SEARCH_WIDTH, 20,
				Component.literal("Search")));
		search.setHint(Component.literal("Search by name or by what it does"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setResponder(text -> {
			query = text;
			showing = list.showAll(text);
		});

		list = addRenderableWidget(new SettingsList(minecraft, width, height - HEADER - FOOTER, HEADER, this));

		resetAll = addRenderableWidget(
				Button.builder(Component.literal("Reset all"), button -> resetAll())
						.bounds(width / 2 - BUTTON_WIDTH - 2, height - 28, BUTTON_WIDTH, 20)
						.build());
		resetAll.setTooltip(Tooltip.create(Component.literal(
				"Puts every setting back to the value it shipped with, and empties "
						+ SettingRegistry.file() + ". Does not touch your API keys.")));

		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 + 2, height - 28, BUTTON_WIDTH, 20)
				.build());

		show(advanced);
	}

	/**
	 * Switches tab.
	 *
	 * <p>The search box belongs to the advanced tab alone. On the simple page there are a dozen rows
	 * and the AI panel at the top of them, which is less to read than the search box explaining
	 * itself.</p>
	 */
	private void show(boolean showAdvanced) {
		advanced = showAdvanced;
		simpleTab.active = advanced;
		advancedTab.active = !advanced;
		search.visible = advanced;
		search.setFocused(false);
		showing = advanced ? list.showAll(query) : list.showSimple();
		if (advanced) {
			setInitialFocus(search);
		}
	}

	/**
	 * Opens a chooser over this screen, saving anything half-entered first.
	 *
	 * <p>The saving is the point. Leaving here does not go through {@link #onClose}, so a key that had
	 * been pasted but not yet committed would simply be gone — and while clicking a chooser button does
	 * take focus off the key box, the commit that focus loss triggers happens on the <em>next</em>
	 * frame, which by then belongs to a different screen.</p>
	 */
	void openChooser(Screen chooser) {
		list.commit();
		minecraft.gui.setScreen(chooser);
	}

	/**
	 * Puts every setting back, on the second press.
	 *
	 * <p>Asking twice rather than opening a confirmation screen: this throws away every change ever
	 * made and there is no undo, which is too much to hang on one misplaced click — but it is also not
	 * important enough to be worth a whole screen of its own. Moving the mouse off the button puts the
	 * question away again, so an armed one is never left lying in wait.</p>
	 *
	 * <p>Settings only. API keys live in a different file and are not "settings that shipped with a
	 * default" — losing one to a button labelled "reset" would be a genuinely bad surprise.</p>
	 */
	private void resetAll() {
		if (!resetArmed) {
			resetArmed = true;
			resetAll.setMessage(Component.literal("Reset all — sure?"));
			return;
		}
		SettingRegistry.resetAll();
		disarm();
		// Rebuilt rather than left alone: every row is holding a value that is no longer the one the
		// setting has, and a control showing a stale value is worse than no control at all.
		show(advanced);
	}

	private void disarm() {
		if (resetArmed) {
			resetArmed = false;
			resetAll.setMessage(Component.literal("Reset all"));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (resetArmed && !resetAll.isMouseOver(mouseX, mouseY)) {
			disarm();
		}
		graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		graphics.centeredText(font, counts(), width / 2, height - 54, DIM);
		graphics.centeredText(font, model(), width / 2, height - 42, keyMissing() ? WARN : DIM);
	}

	private Component counts() {
		int changed = SettingRegistry.modified().size();
		int total = SettingRegistry.all().size();
		String shown = advanced && showing != total
				? showing + " of " + total + " showing"
				: total + " settings";
		return Component.literal(shown + " · "
				+ (changed == 0 ? "none changed" : changed + " changed, saved to config/"
						+ SettingRegistry.file().getFileName()));
	}

	/**
	 * Which model is actually going to be asked, and whether it can be.
	 *
	 * <p>Assembled from the provider rather than from any one setting, because "which model" is a
	 * two-part answer — the provider decides which of the six model settings counts — and getting that
	 * wrong is silent everywhere else.</p>
	 */
	private Component model() {
		AiProvider provider = BotSettings.AI_PROVIDER.get();
		StringBuilder text = new StringBuilder("Asking ")
				.append(provider.label())
				.append(": ")
				.append(provider.modelSetting().get());

		List<String> keys = provider.keyNames();
		if (!keys.isEmpty()) {
			text.append(keyMissing()
					? " · no " + keys.getFirst() + " yet — paste one above"
					: " · " + keys.getFirst() + " found");
		}
		return Component.literal(text.toString());
	}

	private boolean keyMissing() {
		List<String> keys = BotSettings.AI_PROVIDER.get().keyNames();
		return !keys.isEmpty() && !ApiKeys.has(keys);
	}

	/**
	 * Keeps the game running underneath.
	 *
	 * <p>Every other options screen pauses singleplayer, and should. This one is watched while the bot
	 * is working — a route being drawn, a tunnel being dug — and half the settings on it are only
	 * meaningful against that. Pausing would hide the thing being tuned.</p>
	 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		// A key pasted and then closed with Escape never loses focus, so it would never have been
		// saved. Losing a key that was visibly typed in is the worst outcome this screen has.
		list.commit();
		minecraft.gui.setScreen(parent);
	}
}

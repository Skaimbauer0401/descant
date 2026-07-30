package mcbot.client.gui;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.ai.ApiKeys;
import mcbot.client.settings.SettingRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen: everything {@code /mcbot set} can change, with somewhere to click instead.
 *
 * <p>Not a replacement for the command — the command is still what a model uses, and still the
 * quicker way to change one setting you already know the name of. What this adds is the other half:
 * seeing what the settings <em>are</em>. There are getting on for eighty of them, each with a
 * sentence explaining it and a range it accepts, and none of that is discoverable by typing
 * {@code /mcbot set} and reading eighty lines of chat.</p>
 *
 * <p>Two things here exist because of a specific afternoon lost to them, and both are about the
 * model: a model setting the chosen provider does not read is greyed out rather than looking
 * ordinary, and the line above the buttons says which model will actually be asked and whether the
 * key for it was found. Both are questions the chat commands could only answer if you knew to ask.</p>
 *
 * <p>Deliberately not a pause screen. The bot keeps walking, mining and thinking while this is open,
 * which is what makes it possible to nudge a cost and watch the route change rather than guessing.</p>
 */
public final class SettingsScreen extends Screen {

	/** Title, then the search box. */
	private static final int HEADER = 56;
	/** The count line, the status line, then the buttons. */
	private static final int FOOTER = 60;

	private static final int BUTTON_WIDTH = 150;
	private static final int SEARCH_WIDTH = 300;

	private static final int DIM = 0xFFA0A0A0;
	private static final int WARN = 0xFFFF5555;

	/** Where {@code Done} goes back to — normally nothing, but the pause menu if opened from there. */
	private final Screen parent;

	/**
	 * Survives the rebuild that a window resize causes, so resizing does not silently undo a search
	 * and leave the list looking like it lost half the settings.
	 */
	private String query = "";

	private SettingsList list;
	private Button resetAll;
	private boolean resetArmed;
	private int showing;

	public SettingsScreen(Screen parent) {
		super(Component.literal("mcbot settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		EditBox search = new EditBox(font, (width - SEARCH_WIDTH) / 2, 30, SEARCH_WIDTH, 20,
				Component.literal("Search"));
		search.setHint(Component.literal("Search by name or by what it does"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setResponder(text -> {
			query = text;
			showing = list.filter(text);
		});
		addRenderableWidget(search);

		list = new SettingsList(minecraft, width, height - HEADER - FOOTER, HEADER);
		addRenderableWidget(list);
		showing = list.filter(query);

		resetAll = Button.builder(Component.literal("Reset all"), button -> resetAll())
				.bounds(width / 2 - BUTTON_WIDTH - 2, height - 28, BUTTON_WIDTH, 20)
				.build();
		resetAll.setTooltip(Tooltip.create(Component.literal(
				"Puts every setting back to the value it shipped with, and empties "
						+ SettingRegistry.file() + ".")));
		addRenderableWidget(resetAll);

		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 + 2, height - 28, BUTTON_WIDTH, 20)
				.build());

		// Focused on open, so a screen with eighty rows on it can be narrowed to the one you came for
		// without reaching for the mouse first.
		setInitialFocus(search);
	}

	/**
	 * Puts every setting back, on the second press.
	 *
	 * <p>Asking twice rather than opening a confirmation screen: this throws away every change ever
	 * made and there is no undo, which is too much to hang on one misplaced click — but it is also not
	 * important enough to be worth a whole screen of its own. Moving the mouse off the button puts the
	 * question away again, so an armed one is never left lying in wait.</p>
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
		showing = list.filter(query);
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
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.centeredText(font, counts(), width / 2, height - 54, DIM);
		graphics.centeredText(font, model(), width / 2, height - 42,
				keyMissing() ? WARN : DIM);
	}

	private Component counts() {
		int changed = SettingRegistry.modified().size();
		int total = SettingRegistry.all().size();
		String shown = showing == total ? total + " settings" : showing + " of " + total + " showing";
		return Component.literal(shown + " · "
				+ (changed == 0 ? "none changed" : changed + " changed, saved to config/"
						+ SettingRegistry.file().getFileName()));
	}

	/**
	 * Which model is actually going to be asked, and whether it can be.
	 *
	 * <p>Assembled from the provider rather than from any one setting, because "which model" is a
	 * two-part answer — the provider picks which of the four model settings counts — and getting that
	 * wrong is silent everywhere else.</p>
	 */
	private Component model() {
		AiProvider provider = BotSettings.AI_PROVIDER.get();
		StringBuilder text = new StringBuilder("Asking ")
				.append(provider.key())
				.append(": ")
				.append(provider.modelSetting().name())
				.append(" = ")
				.append(provider.modelSetting().get());

		List<String> keys = provider.keyNames();
		if (!keys.isEmpty()) {
			text.append(keyMissing()
					? " · no " + keys.getFirst() + " found — put one in config/"
							+ ApiKeys.file().getFileName()
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
		minecraft.gui.setScreen(parent);
	}
}

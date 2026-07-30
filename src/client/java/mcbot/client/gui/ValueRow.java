package mcbot.client.gui;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * One setting: its name, a control holding its value, and a button putting the default back.
 *
 * <p>Which control follows from the setting's own {@link Setting#options()} — a listed set of values
 * cycles between them, and anything else is typed into. Nothing here is written per setting, which is
 * what lets the screen be generated from {@link SettingRegistry} rather than maintained beside it.</p>
 *
 * <p>Nothing caches the value either. The control is seeded from the setting once, and every colour
 * and tooltip is recomputed as the row is drawn, so a change made from chat or by a model shows up
 * here without anyone having to remember to say so.</p>
 */
final class ValueRow extends Row {

	private final Setting setting;
	private final AbstractWidget control;
	private final Button reset;

	/** Set when the control holds text the setting will not take, or took but could not save. */
	private String problem;
	private boolean rejected;

	/** What the control's tooltip was last written for, so it is rebuilt only when it goes stale. */
	private String tooltipFor;

	/** Exactly one of these is set; the other kind of setting cannot be given a bad value. */
	private final EditBox box;
	private final CycleButton<String> cycle;

	ValueRow(Minecraft minecraft, Setting setting) {
		super(minecraft);
		this.setting = setting;

		if (setting.options().isEmpty()) {
			this.box = new EditBox(minecraft.font, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT,
					Component.literal(setting.name()));
			this.box.setMaxLength(256);
			// Seeded before the responder is attached, so building the row is not itself a change that
			// gets applied and written to disk.
			this.box.setValue(setting.asString());
			this.box.setResponder(this::apply);
			this.cycle = null;
			this.control = box;
		} else {
			this.cycle = CycleButton.builder(Component::literal, setting.asString())
					.withValues(setting.options())
					.displayOnlyValue()
					.create(0, 0, CONTROL_WIDTH, WIDGET_HEIGHT, Component.literal(setting.name()),
							(button, value) -> apply(value));
			this.box = null;
			this.control = cycle;
		}

		this.reset = Button.builder(Component.literal("↺"), button -> restore())
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
		this.reset.setTooltip(Tooltip.create(Component.literal(
				"Back to the default, " + setting.defaultAsString() + ".")));
	}

	// ---------------------------------------------------------------- changing it

	/**
	 * Hands a value to the setting, and keeps whatever it said about it.
	 *
	 * <p>A rejected value is left in the box and shown in red rather than being reverted. Reverting is
	 * the more common design and the more annoying one: it throws away what was being typed halfway
	 * through typing it, and says nothing about why.</p>
	 */
	private void apply(String value) {
		try {
			problem = setting.change(value)
					? null
					: "Changed, but " + SettingRegistry.file()
							+ " could not be written, so this lasts until the game closes.";
			rejected = false;
		} catch (RuntimeException e) {
			problem = e.getMessage();
			rejected = true;
		}
		if (box != null) {
			box.setTextColor(rejected ? REJECTED_COLOUR
					: problem != null ? UNSAVED_COLOUR : EditBox.DEFAULT_TEXT_COLOR);
		}
		retooltip();
	}

	/**
	 * Keeps the control's own tooltip current.
	 *
	 * <p>Rebuilt only when what it would say has changed, because {@link Tooltip} splits its text into
	 * lines once and caches them — replacing it every frame would throw that away sixty times a second
	 * for text nobody is reading.</p>
	 */
	private void retooltip() {
		String wanted = problem != null
				? problem
				: setting.isDefault() + "|" + BotSettings.AI_PROVIDER.get().key();
		if (wanted.equals(tooltipFor)) {
			return;
		}
		tooltipFor = wanted;
		control.setTooltip(Tooltip.create(
				problem != null ? Component.literal(problem) : explanation()));
	}

	/** Puts the default back, in the setting, on disk, and in the control showing it. */
	private void restore() {
		setting.restore();
		problem = null;
		rejected = false;
		retooltip();
		if (box != null) {
			box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
			box.setValue(setting.asString());
		} else {
			cycle.setValue(setting.asString());
		}
	}

	// ---------------------------------------------------------------- drawing it

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			float partialTick) {
		reset.active = !setting.isDefault();
		retooltip();

		int labelStop = place(graphics, mouseX, mouseY, partialTick, control, reset);
		drawLabel(graphics, setting.name(), labelStop, colour(), this::explanation, mouseX, mouseY);
	}

	private int colour() {
		if (inertReader() != null) {
			return INERT_COLOUR;
		}
		return setting.isDefault() ? LABEL_COLOUR : CHANGED_COLOUR;
	}

	/**
	 * The provider this setting belongs to, when that is not the one running.
	 *
	 * <p>Six model settings a word apart, where changing the wrong one succeeds, saves and does
	 * nothing. Greying it is the cheapest possible warning: it costs no words, and it changes the
	 * moment the provider is switched. The simple tab avoids the question altogether by showing one
	 * model row that follows the provider; this is for the full list, where all six are on show.</p>
	 */
	private AiProvider inertReader() {
		AiProvider reader = AiProvider.readerOf(setting);
		return reader == null || reader == BotSettings.AI_PROVIDER.get() ? null : reader;
	}

	private Component explanation() {
		StringBuilder text = new StringBuilder(setting.description());
		text.append(" Accepts ").append(setting.domain()).append('.');
		if (!setting.isDefault()) {
			text.append(" The default is ").append(setting.defaultAsString()).append('.');
		}
		AiProvider inert = inertReader();
		if (inert != null) {
			text.append("\n\nNot in use: this is read only when aiProvider is '")
					.append(inert.key()).append("', and it is '")
					.append(BotSettings.AI_PROVIDER.get().key()).append("'.");
		}
		return Component.literal(text.toString());
	}

	@Override
	protected List<? extends AbstractWidget> widgets() {
		return List.of(control, reset);
	}
}

package mcbot.client.gui;

import java.util.List;
import java.util.Locale;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

/**
 * The scrolling body of {@link SettingsScreen}: one row per setting, under the heading it was
 * declared beneath.
 *
 * <p>Built from {@link SettingRegistry} rather than written out, for the same reason
 * {@code /mcbot set} offers the setting names from there — a setting added to
 * {@link BotSettings} tomorrow appears here on its own, and one that is renamed cannot leave a dead
 * row behind. Which control a row gets follows from the setting's own {@link Setting#options()}:
 * something with a listed set of values cycles between them, and everything else is typed into.</p>
 *
 * <p>Nothing here caches the setting's value. The controls are seeded from it once and every colour,
 * tooltip and enablement is recomputed as the row is drawn, so a change made from chat, from a
 * model, or from another row shows up here without anyone having to remember to say so.</p>
 */
final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {

	private static final int ROW_HEIGHT = 24;
	private static final int WIDGET_HEIGHT = 20;
	private static final int CONTROL_WIDTH = 110;
	private static final int RESET_WIDTH = 20;
	private static final int GAP = 4;
	private static final int ROW_WIDTH = 340;

	/** Text at rest. */
	private static final int LABEL_COLOUR = 0xFFE0E0E0;
	/** A setting that has been moved off its default, so the changed ones stand out while scrolling. */
	private static final int CHANGED_COLOUR = 0xFFFFFF55;
	/** A model setting the chosen provider does not read — real, saved, and having no effect. */
	private static final int INERT_COLOUR = 0xFF808080;
	private static final int HEADING_COLOUR = 0xFFFFAA00;
	/** A value the setting refused. Alpha included: these go where {@code 0xFFE0E0E0} is the norm. */
	private static final int REJECTED_COLOUR = 0xFFFF5555;
	/** A value that took effect but could not be written down. */
	private static final int UNSAVED_COLOUR = 0xFFFFAA00;

	SettingsList(Minecraft minecraft, int width, int height, int y) {
		super(minecraft, width, height, y, ROW_HEIGHT);
	}

	@Override
	public int getRowWidth() {
		return ROW_WIDTH;
	}

	/**
	 * Rebuilds the list, keeping the settings whose name or description contains {@code query}.
	 *
	 * <p>Description as well as name, because the name is what you type once you already know it. The
	 * useful search is the other way round — "durability", "chest", "jump" — and those words are in
	 * the sentence rather than in {@code lowDurability}.</p>
	 *
	 * @return how many settings are showing
	 */
	int filter(String query) {
		clearEntries();
		String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

		String heading = null;
		int shown = 0;
		for (Setting setting : SettingRegistry.all()) {
			if (!matches(setting, wanted)) {
				continue;
			}
			// Emitted lazily, so a group whose settings were all filtered out leaves no empty heading.
			String group = SettingRegistry.groupOf(setting);
			if (!group.equals(heading)) {
				heading = group;
				addEntry(new GroupRow(group));
			}
			addEntry(new SettingRow(setting));
			shown++;
		}
		setScrollAmount(0.0);
		return shown;
	}

	private static boolean matches(Setting setting, String wanted) {
		return wanted.isEmpty()
				|| setting.name().toLowerCase(Locale.ROOT).contains(wanted)
				|| setting.description().toLowerCase(Locale.ROOT).contains(wanted);
	}

	// ================================================================ rows

	abstract class Row extends ContainerObjectSelectionList.Entry<Row> {
	}

	/** A section heading. Not focusable and not clickable — it is a label with a row to itself. */
	private final class GroupRow extends Row {

		private final Component label;

		private GroupRow(String heading) {
			this.label = Component.literal(heading);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float partialTick) {
			graphics.text(minecraft.font, label, getContentX(),
					getContentY() + (getContentHeight() - minecraft.font.lineHeight) / 2 + 2,
					HEADING_COLOUR);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}

	/** One setting: its name, a control holding its value, and a button putting the default back. */
	private final class SettingRow extends Row {

		private final Setting setting;
		private final AbstractWidget control;
		private final Button reset;

		/** Set when the control holds text the setting will not take, or took but could not save. */
		private String problem;
		private boolean rejected;

		/** What the control's tooltip was last written for, so it is rebuilt only when it goes stale. */
		private String tooltipFor;

		/** Non-null only for a free-value setting; the other kind cannot be given a bad value. */
		private final EditBox box;
		private final CycleButton<String> cycle;

		private SettingRow(Setting setting) {
			this.setting = setting;

			if (setting.options().isEmpty()) {
				this.box = new EditBox(minecraft.font, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT,
						Component.literal(setting.name()));
				this.box.setMaxLength(256);
				// Seeded before the responder is attached, so setting up the row is not itself a change
				// that gets applied and written to disk.
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
					.size(RESET_WIDTH, WIDGET_HEIGHT)
					.build();
			this.reset.setTooltip(Tooltip.create(Component.literal(
					"Back to the default, " + setting.defaultAsString() + ".")));
		}

		// ---------------------------------------------------------------- changing it

		/**
		 * Hands a value to the setting, and keeps whatever it said about it.
		 *
		 * <p>A rejected value is left in the box and shown in red rather than being reverted. Reverting
		 * is the more common design and the more annoying one: it throws away what was being typed
		 * halfway through typing it, and says nothing about why.</p>
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
		 * <p>Rebuilt only when what it would say has changed, because {@link Tooltip} splits its text
		 * into lines once and caches them — replacing it every frame would throw that away sixty times a
		 * second for text nobody is reading.</p>
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
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float partialTick) {
			int top = getContentY() + (getContentHeight() - WIDGET_HEIGHT) / 2;

			// Positioned as it is drawn rather than once at construction: the row moves as the list is
			// scrolled and is reused at a different width when the window is resized.
			reset.setX(getContentRight() - RESET_WIDTH);
			reset.setY(top);
			reset.active = !setting.isDefault();

			control.setX(reset.getX() - GAP - CONTROL_WIDTH);
			control.setY(top);
			retooltip();

			control.extractRenderState(graphics, mouseX, mouseY, partialTick);
			reset.extractRenderState(graphics, mouseX, mouseY, partialTick);

			int labelRight = control.getX() - GAP;
			String name = minecraft.font.plainSubstrByWidth(setting.name(), labelRight - getContentX());
			graphics.text(minecraft.font, name, getContentX(),
					top + (WIDGET_HEIGHT - minecraft.font.lineHeight) / 2 + 1, colour());

			// The name is the biggest target in the row and the least self-explanatory thing in it, so
			// it answers a hover too rather than sending you to the control to find out what it means.
			if (mouseX >= getContentX() && mouseX < labelRight && mouseY >= top
					&& mouseY < top + WIDGET_HEIGHT) {
				graphics.setTooltipForNextFrame(minecraft.font, explanation(), mouseX, mouseY);
			}
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
		 * <p>There are four model settings a word apart, and changing the wrong one succeeds, saves and
		 * does nothing. Greying it here is the cheapest possible warning: it costs no words, and it
		 * changes the moment the provider above it is switched.</p>
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

		// ---------------------------------------------------------------- plumbing

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(control, reset);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(control, reset);
		}
	}
}

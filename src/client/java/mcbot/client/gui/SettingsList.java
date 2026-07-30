package mcbot.client.gui;

import java.util.List;
import java.util.Locale;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingRegistry;
import mcbot.client.settings.SettingRegistry.Tier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * The scrolling body of {@link SettingsScreen}, in either of the two shapes it takes.
 *
 * <p><b>Simple</b> is a designed page: the provider, the model that follows it, and somewhere to put
 * the key, followed by the settings that are preferences rather than tuning. <b>Advanced</b> is every
 * setting there is, generated from {@link SettingRegistry} and searchable.</p>
 *
 * <p>The split is carried by the settings themselves, as a {@link Tier} on the group each was
 * declared under — so a setting added tomorrow lands on the right page without this class being
 * told. The line is drawn by what a wrong value does: a preference you disagree with, or a bot that
 * walks into walls.</p>
 */
final class SettingsList extends ContainerObjectSelectionList<Row> {

	/**
	 * Kept across rebuilds rather than made fresh each time.
	 *
	 * <p>They hold state that is not in any setting — a model list being fetched, a key part-way
	 * through being pasted — and a rebuild that dropped it would lose whatever was in hand at whatever
	 * moment the list happened to be rebuilt.</p>
	 */
	private final ProviderRow providerRow;
	private final ModelRow modelRow;
	private final KeyRow keyRow;

	SettingsList(Minecraft minecraft, int width, int height, int y, SettingsScreen screen) {
		super(minecraft, width, height, y, Row.ROW_HEIGHT);
		this.providerRow = new ProviderRow(minecraft, screen);
		this.modelRow = new ModelRow(minecraft, screen);
		this.keyRow = new KeyRow(minecraft);
	}

	@Override
	public int getRowWidth() {
		return Row.ROW_WIDTH;
	}

	/** Writes out anything typed but not yet saved. Called when the screen closes. */
	void commit() {
		keyRow.commit();
	}

	// ---------------------------------------------------------------- the two pages

	/**
	 * The everyday page: how the bot thinks, then what it is allowed to do.
	 *
	 * <p>The model group comes first regardless of where it was declared, because it is what people
	 * open this screen for. Its own three rows replace six settings: {@code aiProvider} is the
	 * provider row, and all six model settings are the one model row, which is pointed at whichever of
	 * them is live.</p>
	 *
	 * @return how many rows carry a value
	 */
	int showSimple() {
		clearEntries();
		String modelGroup = SettingRegistry.groupOf(BotSettings.AI_PROVIDER);

		addEntry(new GroupRow(minecraft, modelGroup));
		addEntry(providerRow);
		addEntry(modelRow);
		addEntry(keyRow);
		int shown = 3;

		for (Setting setting : SettingRegistry.all()) {
			if (isSimple(setting) && SettingRegistry.groupOf(setting).equals(modelGroup)) {
				addEntry(new ValueRow(minecraft, setting));
				shown++;
			}
		}

		String heading = null;
		for (Setting setting : SettingRegistry.all()) {
			String group = SettingRegistry.groupOf(setting);
			if (!isSimple(setting) || group.equals(modelGroup)) {
				continue;
			}
			if (!group.equals(heading)) {
				heading = group;
				addEntry(new GroupRow(minecraft, group));
			}
			addEntry(new ValueRow(minecraft, setting));
			shown++;
		}

		setScrollAmount(0.0);
		return shown;
	}

	/**
	 * Whether a setting gets a row of its own on the simple page.
	 *
	 * <p>The provider and the six model names are excluded because the three purpose-built rows above
	 * already cover them — and showing {@code aiClaudeModel} next to a model row that is pointed at
	 * {@code aiOllamaModel} would put the trap those rows exist to remove straight back on the
	 * screen.</p>
	 */
	private static boolean isSimple(Setting setting) {
		return SettingRegistry.tierOf(setting) == Tier.SIMPLE
				&& setting != BotSettings.AI_PROVIDER
				&& AiProvider.readerOf(setting) == null;
	}

	/**
	 * Every setting there is, filtered by {@code query}.
	 *
	 * <p>Everything, not just the advanced half: this is the page that has a search box, and a search
	 * that quietly refuses to find {@code showPath} because it lives on the other tab is a search that
	 * cannot be trusted. Nothing is unreachable from here.</p>
	 *
	 * @return how many settings are showing
	 */
	int showAll(String query) {
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
				addEntry(new GroupRow(minecraft, group));
			}
			addEntry(new ValueRow(minecraft, setting));
			shown++;
		}
		setScrollAmount(0.0);
		return shown;
	}

	/**
	 * Description as well as name.
	 *
	 * <p>The name is what you type once you already know it. The useful search is the other way round
	 * — "durability", "chest", "jump" — and those words are in the sentence rather than in
	 * {@code lowDurability}.</p>
	 */
	private static boolean matches(Setting setting, String wanted) {
		return wanted.isEmpty()
				|| setting.name().toLowerCase(Locale.ROOT).contains(wanted)
				|| setting.description().toLowerCase(Locale.ROOT).contains(wanted);
	}

	// ---------------------------------------------------------------- headings

	/** A section heading. Not focusable and not clickable — a label with a row to itself. */
	private static final class GroupRow extends Row {

		private final Component label;

		private GroupRow(Minecraft minecraft, String heading) {
			super(minecraft);
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
		protected List<? extends AbstractWidget> widgets() {
			return List.of();
		}
	}
}

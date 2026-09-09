package descant.client.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pick one thing from a list, with the option of typing something that is not on it.
 *
 * <p>Minecraft has no dropdown widget, and the usual mod answer — drawing a floating panel over the
 * page — fights the scissor rectangle of the list it would have to hang out of, and has nowhere to go
 * when the list is sixty entries long. A screen of its own has neither problem: it scrolls, it can
 * hold a search-sized list, and it has room to say <em>why</em> one of the options is the one to
 * pick, which is most of the value here.</p>
 *
 * <p>The free-text box is the escape hatch. A list of models is a snapshot of what a provider
 * advertised, and the name you want may be newer than the snapshot, unadvertised, or a fine-tune of
 * something else — so there is always a way to write one in rather than being limited to what this
 * screen happened to know about.</p>
 */
public final class ChoiceScreen extends Screen {

	/**
	 * One thing that can be picked.
	 *
	 * @param value       what gets stored if this is chosen
	 * @param label       what to show, which need not be the value — "Ollama cloud" for {@code cloud}
	 * @param note        a sentence on what picking it means, shown on hover; may be empty
	 * @param recommended whether to mark it as the one to pick
	 * @param unconfirmed whether this is a suggestion the provider itself did not list, and so may no
	 *                    longer exist — shown dimmed rather than hidden, because a name that is merely
	 *                    unadvertised often still works
	 */
	public record Option(String value, String label, String note, boolean recommended,
			boolean unconfirmed) {

		public static Option of(String value) {
			return new Option(value, value, "", false, false);
		}
	}

	private static final int HEADER = 44;
	private static final int FOOTER = 66;
	private static final int BUTTON_WIDTH = 150;
	private static final int CUSTOM_WIDTH = 240;

	private static final int DIM = 0xFFA0A0A0;

	private final Screen parent;
	private final List<Option> options;
	private final String current;
	private final String customHint;
	private final Consumer<String> chosen;

	/**
	 * @param customHint what to show in the free-text box, or {@code null} for a closed list
	 * @param chosen     run with the chosen value; the screen closes itself either way
	 */
	public ChoiceScreen(Screen parent, Component title, List<Option> options, String current,
			String customHint, Consumer<String> chosen) {
		super(title);
		this.parent = parent;
		this.options = List.copyOf(options);
		this.current = current;
		this.customHint = customHint;
		this.chosen = chosen;
	}

	@Override
	protected void init() {
		ChoiceList list = new ChoiceList(minecraft, width, height - HEADER - FOOTER, HEADER);
		list.fill(options);
		addRenderableWidget(list);

		if (customHint != null) {
			EditBox custom = new EditBox(font, (width - CUSTOM_WIDTH) / 2 - 26, height - 52,
					CUSTOM_WIDTH, 20, Component.literal("Other"));
			custom.setHint(Component.literal(customHint));
			custom.setMaxLength(128);
			addRenderableWidget(custom);

			addRenderableWidget(Button.builder(Component.literal("Use"), button -> {
						if (!custom.getValue().isBlank()) {
							pick(custom.getValue().trim());
						}
					})
					.bounds(custom.getRight() + 4, height - 52, 44, 20)
					.build());
		}

		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
				.bounds((width - BUTTON_WIDTH) / 2, height - 26, BUTTON_WIDTH, 20)
				.build());
	}

	private void pick(String value) {
		chosen.accept(value);
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("now: " + current), width / 2, 28, DIM);
	}

	/** Like the settings screen it was opened from, so the bot does not stop while you choose. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ================================================================ the list

	private final class ChoiceList extends ContainerObjectSelectionList<Row> {

		private ChoiceList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, Row.ROW_HEIGHT);
		}

		@Override
		public int getRowWidth() {
			return Row.ROW_WIDTH;
		}

		private void fill(List<Option> options) {
			clearEntries();
			options.forEach(option -> addEntry(new OptionRow(minecraft, option)));
		}
	}

	/** One option, as a button spanning the row. */
	private final class OptionRow extends Row {

		private final Option option;
		private final Button button;

		private OptionRow(Minecraft minecraft, Option option) {
			super(minecraft);
			this.option = option;

			this.button = Button.builder(Component.literal(label()), press -> pick(option.value()))
					.size(Row.ROW_WIDTH, WIDGET_HEIGHT)
					.build();
			if (!option.note().isEmpty()) {
				this.button.setTooltip(Tooltip.create(Component.literal(option.note())));
			}
		}

		/**
		 * The button's text, carrying its own annotations.
		 *
		 * <p>All of it in the label rather than in a second column: a row is twenty pixels tall, and the
		 * one thing somebody scanning this list needs to see without hovering is which entry is the
		 * recommended one and which is the one already selected.</p>
		 */
		private String label() {
			StringBuilder text = new StringBuilder();
			if (option.value().equals(current)) {
				text.append("✔ ");
			}
			text.append(option.label());
			if (option.recommended()) {
				text.append("  ★ recommended");
			}
			if (option.unconfirmed()) {
				text.append("  (unlisted)");
			}
			return text.toString();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float partialTick) {
			button.setX(getContentX());
			button.setY(widgetTop());
			button.setWidth(getContentWidth());
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}

		@Override
		protected List<? extends AbstractWidget> widgets() {
			return List.of(button);
		}
	}
}

package descant.client.gui;

import java.util.Arrays;
import java.util.List;

import descant.client.BotSettings;
import descant.client.ai.AiProvider;
import descant.client.ai.ApiKeys;
import descant.client.ai.ModelCatalogue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Which provider drives the bot, chosen from a list rather than cycled through.
 *
 * <p>Six options, each with a reason to pick it and one with a reason not to, is more than a cycling
 * button can carry — that widget shows one value at a time and says nothing about the others, so
 * finding the recommended one means clicking through all six and remembering. {@link ChoiceScreen}
 * shows them at once, marks the recommendation, and has room for the sentence explaining it.</p>
 */
final class ProviderRow extends Row {

	private final SettingsScreen screen;
	private final Button open;

	ProviderRow(Minecraft minecraft, SettingsScreen screen) {
		super(minecraft);
		this.screen = screen;
		this.open = Button.builder(Component.literal(""), button -> choose())
				.size(CONTROL_WIDTH + GAP + BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
	}

	private static AiProvider provider() {
		return BotSettings.AI_PROVIDER.get();
	}

	private void choose() {
		List<ChoiceScreen.Option> options = Arrays.stream(AiProvider.values())
				.map(candidate -> new ChoiceScreen.Option(
						candidate.key(),
						candidate.label() + keyState(candidate),
						candidate.advice(),
						candidate.recommended(),
						false))
				.toList();

		screen.openChooser(new ChoiceScreen(screen,
				Component.literal("Which model drives the bot"),
				options, provider().key(), null,
				value -> {
					BotSettings.AI_PROVIDER.change(value);
					// The model list belongs to the provider, so a switch is the moment to go and get
					// the new one rather than when somebody next opens the model chooser.
					ModelCatalogue.request(provider());
				}));
	}

	/**
	 * Whether this provider could be used right now, appended to its name.
	 *
	 * <p>The single most useful thing to know while choosing, and the thing that used to require
	 * choosing one to find out. A provider whose key is missing is still selectable — you may be about
	 * to paste one — but it should not look identical to one that is ready.</p>
	 */
	private static String keyState(AiProvider candidate) {
		if (!candidate.needsKey()) {
			return "";
		}
		return ApiKeys.has(candidate.keyNames()) ? "  (key ready)" : "  (needs a key)";
	}

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			float partialTick) {
		AiProvider provider = provider();
		open.setMessage(Component.literal(provider.label()
				+ (provider.recommended() ? "  ★" : "")));
		open.setTooltip(Tooltip.create(Component.literal(provider.advice())));

		// Laid out as a control with a button's worth of extra width, so it lines up with the rows
		// under it rather than with nothing.
		int right = getContentRight();
		open.setX(right - open.getWidth());
		open.setY(widgetTop());
		open.extractRenderState(graphics, mouseX, mouseY, partialTick);

		drawLabel(graphics, "provider", open.getX() - GAP,
				BotSettings.AI_PROVIDER.isDefault() ? LABEL_COLOUR : CHANGED_COLOUR,
				() -> Component.literal("Where the model that drives the bot comes from. "
						+ provider.advice()
						+ "\n\nStored as aiProvider. Click to see all six, what each one costs, and "
						+ "which of them already has a key."),
				mouseX, mouseY);
	}

	@Override
	protected List<? extends AbstractWidget> widgets() {
		return List.of(open);
	}
}

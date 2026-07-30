package mcbot.client.gui;

import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.ai.ModelCatalogue;
import mcbot.client.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The model, whichever provider is chosen.
 *
 * <p>One row standing in for six settings. Each provider keeps its own model name — switching away
 * and back gets your choice back rather than a default — but only one of those settings is ever the
 * live one, and this row is always pointed at that one. That is the whole answer to the trap that has
 * caught this project twice: there is no way to set the wrong provider's model from here, because the
 * wrong provider's model is not on the screen.</p>
 *
 * <p>Clicking opens a {@link ChoiceScreen} rather than cycling. A cycling button was tolerable for
 * four names and absurd for the sixty a hosted provider will list, and it could not show which of
 * them is the one to pick — which, given the whole point of the list is that most entries are wrong
 * for this job, is the only thing worth showing.</p>
 *
 * <p>The list always has something in it. {@link ModelCatalogue#choices} merges what the provider
 * actually listed with what this mod would suggest, so the chooser works before the fetch finishes,
 * without a key, and offline — and anything the provider did not confirm is marked rather than
 * quietly offered as current.</p>
 */
final class ModelRow extends Row {

	private final SettingsScreen screen;
	private final Button open;
	private final Button refresh;
	private final Button reset;

	ModelRow(Minecraft minecraft, SettingsScreen screen) {
		super(minecraft);
		this.screen = screen;

		this.open = Button.builder(Component.literal(""), button -> choose())
				.size(CONTROL_WIDTH, WIDGET_HEIGHT)
				.build();

		this.refresh = Button.builder(Component.literal("⟳"),
						button -> ModelCatalogue.refresh(provider()))
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
		this.refresh.setTooltip(Tooltip.create(Component.literal(
				"Ask the provider what models it has, again.")));

		this.reset = Button.builder(Component.literal("↺"), button -> setting().restore())
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
	}

	private static AiProvider provider() {
		return BotSettings.AI_PROVIDER.get();
	}

	private static StringSetting setting() {
		return provider().modelSetting();
	}

	// ---------------------------------------------------------------- choosing

	private void choose() {
		AiProvider provider = provider();
		// Asked again here as well as while drawing, so a chooser opened straight after switching
		// provider is not stuck with whatever the previous fetch left behind.
		ModelCatalogue.request(provider);

		String recommended = provider.recommendedModel();
		List<ChoiceScreen.Option> options = ModelCatalogue.choices(provider, setting().get()).stream()
				.map(model -> new ChoiceScreen.Option(
						model,
						model,
						note(provider, model, recommended),
						model.equals(recommended),
						!ModelCatalogue.confirmed(provider, model)))
				.toList();

		screen.openChooser(new ChoiceScreen(screen,
				Component.literal("Which " + provider.label() + " model"),
				options, setting().get(),
				"a model id not on the list",
				value -> setting().change(value)));
	}

	private static String note(AiProvider provider, String model, String recommended) {
		if (model.equals(recommended)) {
			return "The one to start with for " + provider.label()
					+ ": about the capability this job needs, which is reliable function choice rather "
					+ "than deep reasoning, at the lowest price that delivers it.";
		}
		if (!ModelCatalogue.confirmed(provider, model)) {
			return ModelCatalogue.state(provider) == ModelCatalogue.State.READY
					? provider.label() + " did not list this one, so it may have been withdrawn — or it "
							+ "may simply be unadvertised, which happens and still works. Try it and see; "
							+ "a model that is really gone says so on the first request."
					: "A suggestion. " + provider.label() + "'s own list has not arrived yet, so nothing "
							+ "here has been confirmed against it.";
		}
		return provider.label() + " lists this one. Not every model can call tools, and one that "
				+ "cannot says so on the first request.";
	}

	// ---------------------------------------------------------------- drawing it

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			float partialTick) {
		AiProvider provider = provider();
		// Asked for the first time as the row is first drawn, rather than when it is built: the answer
		// is only interesting while somebody is looking at it.
		ModelCatalogue.request(provider);

		open.setMessage(Component.literal(setting().get()));
		open.setTooltip(Tooltip.create(explanation(provider)));

		reset.active = !setting().isDefault();
		reset.setTooltip(Tooltip.create(Component.literal("Back to the recommended model for "
				+ provider.label() + ", " + setting().defaultAsString() + ".")));

		int labelStop = place(graphics, mouseX, mouseY, partialTick, open, reset, refresh);
		drawLabel(graphics, "model" + status(provider), labelStop, colour(provider),
				() -> explanation(provider), mouseX, mouseY);
	}

	private static String status(AiProvider provider) {
		return switch (ModelCatalogue.state(provider)) {
			case LOADING -> " (asking…)";
			case FAILED -> " (suggestions only)";
			case UNASKED, READY -> "";
		};
	}

	private static int colour(AiProvider provider) {
		return switch (ModelCatalogue.state(provider)) {
			case FAILED -> UNSAVED_COLOUR;
			case LOADING -> INERT_COLOUR;
			case UNASKED, READY -> setting().isDefault() ? LABEL_COLOUR : CHANGED_COLOUR;
		};
	}

	private static Component explanation(AiProvider provider) {
		StringBuilder text = new StringBuilder("Which ").append(provider.label())
				.append(" model drives the bot. Stored as ").append(setting().name())
				.append(", so each provider remembers its own. Click to choose, or to type in a name "
						+ "that is not on the list.");

		switch (ModelCatalogue.state(provider)) {
			case READY -> text.append("\n\n").append(ModelCatalogue.models(provider).size())
					.append(" models listed by ").append(provider.label()).append(" for this key.");
			case FAILED -> text.append("\n\nCouldn't ask ").append(provider.label())
					.append(" what it has: ").append(ModelCatalogue.problem(provider))
					.append("\n\nThe suggested names are still offered, and ⟳ tries again.");
			case LOADING -> text.append("\n\nAsking ").append(provider.label()).append(" what it has.");
			case UNASKED -> {
			}
		}
		return Component.literal(text.toString());
	}

	@Override
	protected List<? extends AbstractWidget> widgets() {
		return List.of(open, reset, refresh);
	}
}

package mcbot.client.gui;

import java.util.ArrayList;
import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.ai.AiProvider;
import mcbot.client.ai.ModelCatalogue;
import mcbot.client.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
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
 * <p>The values come from {@link ModelCatalogue}, which asks the provider. A hardcoded list would be
 * wrong within weeks and wrong silently. Until an answer arrives — and if none ever does, because the
 * key is missing or the machine is offline — the row is a text box instead, so a name can always be
 * typed even when nothing can be listed.</p>
 */
final class ModelRow extends Row {

	private final Button refresh;
	private final Button reset;

	/** Rebuilt whenever the provider or the catalogue changes; never null after the first draw. */
	private AbstractWidget control;

	/** What {@link #control} was built for, so it is rebuilt exactly when it has gone stale. */
	private String builtFor = "";

	ModelRow(Minecraft minecraft) {
		super(minecraft);

		this.refresh = Button.builder(Component.literal("⟳"),
						button -> ModelCatalogue.refresh(provider()))
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
		this.refresh.setTooltip(Tooltip.create(Component.literal(
				"Ask the provider what models it has, again.")));

		this.reset = Button.builder(Component.literal("↺"), button -> {
					setting().restore();
					builtFor = "";
				})
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
	}

	private static AiProvider provider() {
		return BotSettings.AI_PROVIDER.get();
	}

	private static StringSetting setting() {
		return provider().modelSetting();
	}

	// ---------------------------------------------------------------- the control

	/**
	 * Rebuilds the control when what it should offer has changed.
	 *
	 * <p>Keyed on the provider, the state of its catalogue and the value itself. A cycling button is
	 * built around a fixed list, so a list that has grown means a new button — there is no way to add
	 * to one in place.</p>
	 */
	private void rebuildIfStale() {
		AiProvider provider = provider();
		List<String> models = ModelCatalogue.models(provider);
		String wanted = provider.key() + "|" + ModelCatalogue.state(provider) + "|" + models.size()
				+ "|" + setting().get();
		if (wanted.equals(builtFor)) {
			return;
		}
		builtFor = wanted;

		if (models.isEmpty()) {
			EditBox box = new EditBox(minecraft.font, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT,
					Component.literal("model"));
			box.setMaxLength(128);
			box.setValue(setting().get());
			box.setResponder(this::apply);
			control = box;
			return;
		}

		// The current value is always offered, even when the provider did not list it. It may be a
		// name that still works and simply is not advertised — and a picker that cannot show what is
		// already selected is a picker that changes the setting just by being opened.
		List<String> values = new ArrayList<>(models);
		if (!values.contains(setting().get())) {
			values.addFirst(setting().get());
		}
		control = CycleButton.builder(Component::literal, setting().get())
				.withValues(values)
				.displayOnlyValue()
				.create(0, 0, CONTROL_WIDTH, WIDGET_HEIGHT, Component.literal("model"),
						(button, value) -> apply(value));
	}

	private void apply(String value) {
		try {
			setting().change(value);
			// Deliberately not rebuilding here: doing so mid-keystroke would replace the box being
			// typed into. The key includes the value, so the next frame picks it up.
			builtFor = provider().key() + "|" + ModelCatalogue.state(provider()) + "|"
					+ ModelCatalogue.models(provider()).size() + "|" + value;
		} catch (RuntimeException e) {
			// A blank name is the only thing a StringSetting refuses, and it happens while clearing the
			// box to type a new one. Nothing to report; the old value stands until a real one arrives.
		}
	}

	// ---------------------------------------------------------------- drawing it

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			float partialTick) {
		AiProvider provider = provider();
		// Asked for the first time as the row is first drawn, rather than when it is built: the answer
		// is only interesting while somebody is looking at it.
		ModelCatalogue.request(provider);
		rebuildIfStale();

		reset.active = !setting().isDefault();
		reset.setTooltip(Tooltip.create(Component.literal(
				"Back to the default for " + provider.label() + ", " + setting().defaultAsString() + ".")));

		int labelStop = place(graphics, mouseX, mouseY, partialTick, control, reset, refresh);
		drawLabel(graphics, "model" + status(provider), labelStop, colour(provider),
				() -> explanation(provider), mouseX, mouseY);
	}

	private static String status(AiProvider provider) {
		return switch (ModelCatalogue.state(provider)) {
			case LOADING -> " (asking…)";
			case FAILED -> " (type it in)";
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
				.append(", so each provider remembers its own.");

		if (ModelCatalogue.state(provider) == ModelCatalogue.State.FAILED) {
			text.append("\n\nCouldn't list them: ").append(ModelCatalogue.problem(provider))
					.append("\n\nThe name can still be typed in, and ⟳ tries again.");
		} else if (ModelCatalogue.state(provider) == ModelCatalogue.State.READY) {
			text.append("\n\n").append(ModelCatalogue.models(provider).size())
					.append(" models listed by ").append(provider.label())
					.append(" for this key. Not all of them can call tools, and one that cannot says so "
							+ "on the first request.");
		}
		return Component.literal(text.toString());
	}

	@Override
	protected List<? extends AbstractWidget> widgets() {
		// control is null until the first draw, and the list asks for this before then.
		return control == null ? List.of(reset, refresh) : List.of(control, reset, refresh);
	}
}

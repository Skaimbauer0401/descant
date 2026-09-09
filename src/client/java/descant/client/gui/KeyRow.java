package descant.client.gui;

import java.util.List;

import descant.client.BotSettings;
import descant.client.ai.AiProvider;
import descant.client.ai.ApiKeys;
import descant.client.ai.ModelCatalogue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Somewhere to paste the API key the chosen provider needs.
 *
 * <p>The key goes into {@code config/descant-keys.properties} — never into a setting. That rule has not
 * moved: settings are listed by {@code /descant set}, echoed into chat and written into the action
 * schema the model itself reads, which are three separate ways for a credential to end up somewhere
 * it cannot be taken back from. What is new is only that the file can now be filled in from here
 * instead of by hand, which is the part that was genuinely awkward.</p>
 *
 * <h2>Three rules this row follows</h2>
 *
 * <ul>
 * <li><b>A stored key is never loaded back into the box.</b> It is written, and after that the screen
 * cannot show it even to itself. Masking what is displayed would still leave the real characters one
 * screenshot of a debug overlay away; not having them is stronger.</li>
 * <li><b>What is typed is masked as it is typed</b>, because that is when somebody is streaming.</li>
 * <li><b>An empty box means "leave it alone", not "delete it".</b> The box starts empty every time, so
 * treating empty as a deletion would destroy a working key on any accidental focus. Forgetting one is
 * its own button.</li>
 * </ul>
 *
 * <p>Saved when the box loses focus rather than as it is typed: a key is pasted in one action, and
 * writing a partial one to disk on every keystroke would mean a half-key on disk any time somebody
 * typed one by hand and got distracted.</p>
 */
final class KeyRow extends Row {

	private final EditBox box;
	private final Button forget;

	private boolean wasFocused;

	/** What happened to the last key saved from here, shown until the provider changes. */
	private String note = "";

	/** So a note about one provider's key does not linger over the next one's row. */
	private AiProvider lastProvider;

	KeyRow(Minecraft minecraft) {
		super(minecraft);

		this.box = new EditBox(minecraft.font, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT,
				Component.literal("API key"));
		this.box.setMaxLength(400);
		this.box.addFormatter((text, offset) ->
				FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));

		this.forget = Button.builder(Component.literal("✕"), button -> forget())
				.size(BUTTON_WIDTH, WIDGET_HEIGHT)
				.build();
		this.forget.setTooltip(Tooltip.create(Component.literal(
				"Remove the saved key from the keys file.")));
	}

	private static AiProvider provider() {
		return BotSettings.AI_PROVIDER.get();
	}

	/** The name this provider's key goes by, or a stand-in for the two that need none. */
	private static String keyName() {
		List<String> names = provider().keyNames();
		return names.isEmpty() ? "API key" : names.getFirst();
	}

	// ---------------------------------------------------------------- saving

	/**
	 * Writes whatever is in the box, if anything, and clears it.
	 *
	 * <p>Called on focus loss and again when the screen closes — the second because closing with
	 * Escape straight after pasting never moves focus, and losing a key that was visibly typed in is
	 * the worst possible outcome for this row.</p>
	 */
	void commit() {
		String typed = box.getValue();
		if (typed.isBlank() || !provider().needsKey()) {
			return;
		}
		box.setValue("");

		AiProvider provider = provider();
		if (!ApiKeys.store(keyName(), typed)) {
			note = "couldn't write " + ApiKeys.file();
			return;
		}
		note = ApiKeys.sourceOf(provider.keyNames()) == ApiKeys.Source.ENVIRONMENT
				// Saved, and about to be ignored. Silence here would be the exact silent no-op this
				// project has been bitten by twice.
				? "saved, but " + keyName() + " is also set in the environment, which wins"
				: "saved";
		// A model list that failed for want of a key should not stay failed now there is one.
		ModelCatalogue.refresh(provider);
	}

	private void forget() {
		box.setValue("");
		AiProvider provider = provider();
		note = ApiKeys.forget(provider.keyNames()) ? "removed" : "couldn't write " + ApiKeys.file();
		ModelCatalogue.refresh(provider);
	}

	// ---------------------------------------------------------------- drawing it

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
			float partialTick) {
		// Focus loss is the save. Checked before anything else so that clicking straight from this box
		// onto the provider button saves the key against the provider it was pasted for.
		if (wasFocused && !box.isFocused()) {
			commit();
		}
		wasFocused = box.isFocused();

		AiProvider provider = provider();
		if (provider != lastProvider) {
			lastProvider = provider;
			box.setValue("");
			note = "";
		}

		ApiKeys.Source source = ApiKeys.sourceOf(provider.keyNames());
		boolean wanted = provider.needsKey();

		// Nothing typed here can override an environment variable of the same name, so the box refuses
		// rather than accepting a key that would be quietly outranked.
		box.setEditable(wanted && source != ApiKeys.Source.ENVIRONMENT);
		box.setHint(Component.literal(!wanted ? "not needed" : switch (source) {
			case ENVIRONMENT -> "from the environment";
			case FILE -> "paste to replace";
			case NONE -> "paste your key";
		}));
		forget.active = source == ApiKeys.Source.FILE;

		int labelStop = place(graphics, mouseX, mouseY, partialTick, box, forget);
		drawLabel(graphics, keyName() + suffix(provider, source), labelStop, colour(provider, source),
				() -> explanation(provider, source), mouseX, mouseY);
	}

	private String suffix(AiProvider provider, ApiKeys.Source source) {
		if (!note.isEmpty()) {
			return " (" + note + ")";
		}
		if (!provider.needsKey()) {
			return " (not needed)";
		}
		return switch (source) {
			case ENVIRONMENT -> " (in the environment)";
			case FILE -> " (saved)";
			case NONE -> " (needed)";
		};
	}

	private int colour(AiProvider provider, ApiKeys.Source source) {
		if (note.startsWith("couldn't") || note.contains("which wins")) {
			return UNSAVED_COLOUR;
		}
		if (!provider.needsKey()) {
			return INERT_COLOUR;
		}
		return switch (source) {
			case ENVIRONMENT, FILE -> GOOD_COLOUR;
			case NONE -> UNSAVED_COLOUR;
		};
	}

	private static Component explanation(AiProvider provider, ApiKeys.Source source) {
		if (!provider.needsKey()) {
			return Component.literal(provider.label() + " needs no key here: it goes through the Ollama "
					+ "daemon on this machine, which holds its own credentials. An Ollama cloud model "
					+ "needs 'ollama signin' run once in a terminal, and nothing after that.");
		}

		StringBuilder text = new StringBuilder("The API key for ").append(provider.label())
				.append(". ").append(provider.keyHelp());

		text.append("\n\nKept in ").append(ApiKeys.file())
				.append(" — never in a setting, so it is never listed, echoed into chat or shown to the "
						+ "model. Read fresh on every request, so it works straight away.");

		if (provider.keyNames().size() > 1) {
			text.append("\n\n").append(String.join(" and ", provider.keyNames()))
					.append(" are both accepted, in that order.");
		}
		if (source == ApiKeys.Source.ENVIRONMENT) {
			text.append("\n\nThis one is set in the environment, which wins over the file and which the "
					+ "game cannot change. Unset it there if you want to use a different key.");
		} else if (source == ApiKeys.Source.FILE) {
			text.append("\n\nA key is saved. It is not shown here — paste a new one over it, or use ✕ "
					+ "to remove it.");
		}
		return Component.literal(text.toString());
	}

	@Override
	protected List<? extends AbstractWidget> widgets() {
		return List.of(box, forget);
	}
}

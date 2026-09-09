package descant.client.gui;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/**
 * One line of the settings screen: a name on the left, a control on the right, and buttons after it.
 *
 * <p>The layout lives here rather than in each row because there are five kinds of row and the only
 * thing that must be identical across all of them is where things sit. A row that positioned its own
 * widgets would drift from the others by two pixels the first time one of them grew a button.</p>
 *
 * <p>Widgets are positioned as they are drawn rather than once when the row is made. They have to be:
 * the row moves as the list is scrolled, and the same row is reused at a different width when the
 * window is resized.</p>
 */
public abstract class Row extends ContainerObjectSelectionList.Entry<Row> {

	static final int ROW_HEIGHT = 24;
	static final int WIDGET_HEIGHT = 20;
	static final int CONTROL_WIDTH = 116;
	static final int BUTTON_WIDTH = 20;
	static final int GAP = 4;
	static final int ROW_WIDTH = 360;

	/** Text at rest. */
	static final int LABEL_COLOUR = 0xFFE0E0E0;
	/** A setting moved off its default, so what has been changed stands out while scrolling. */
	static final int CHANGED_COLOUR = 0xFFFFFF55;
	/** Something real, saved, and having no effect where it is. */
	static final int INERT_COLOUR = 0xFF808080;
	static final int HEADING_COLOUR = 0xFFFFAA00;
	/** A value that was refused. Alpha included: these go where {@code 0xFFE0E0E0} is the norm. */
	static final int REJECTED_COLOUR = 0xFFFF5555;
	/** Something that worked but could not be written down. */
	static final int UNSAVED_COLOUR = 0xFFFFAA00;
	static final int GOOD_COLOUR = 0xFF55FF55;

	protected final Minecraft minecraft;

	protected Row(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	/** The top edge of this row's widgets, centred in whatever height the row was given. */
	protected final int widgetTop() {
		return getContentY() + (getContentHeight() - WIDGET_HEIGHT) / 2;
	}

	/**
	 * Puts the row's widgets in place, draws them, and hands back where the label may run to.
	 *
	 * <p>Right to left: the trailing buttons take the far edge in the order given, then the control,
	 * and the label gets whatever is left. Laying it out from the right is what keeps every control in
	 * the list vertically aligned regardless of how long the names beside them are.</p>
	 *
	 * @param control  the widget holding the value, or {@code null} for a row that has none
	 * @param trailing the buttons after it, outermost last
	 * @return the x the label must stop before
	 */
	protected final int place(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
			AbstractWidget control, AbstractWidget... trailing) {
		int right = getContentRight();
		for (AbstractWidget button : trailing) {
			right -= BUTTON_WIDTH;
			button.setX(right);
			button.setY(widgetTop());
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
			right -= GAP;
		}

		if (control == null) {
			return right;
		}
		control.setX(right - CONTROL_WIDTH);
		control.setY(widgetTop());
		control.extractRenderState(graphics, mouseX, mouseY, partialTick);
		return control.getX() - GAP;
	}

	/**
	 * Draws the row's name, trimmed to fit, and explains it on hover.
	 *
	 * <p>The name is the biggest target in the row and the least self-explanatory thing in it, so it
	 * answers a hover rather than sending you to the control to find out what it means.</p>
	 */
	protected final void drawLabel(GuiGraphicsExtractor graphics, String text, int stopBefore,
			int colour, Supplier<Component> tooltip, int mouseX, int mouseY) {
		int left = getContentX();
		int top = widgetTop();
		graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(text, stopBefore - left),
				left, top + (WIDGET_HEIGHT - minecraft.font.lineHeight) / 2 + 1, colour);

		// A supplier, not a Component: every row assembles a paragraph of explanation, and building
		// fifteen of them per frame to show none of them is a lot of string concatenation to throw away
		// sixty times a second.
		if (mouseX >= left && mouseX < stopBefore && mouseY >= top && mouseY < top + WIDGET_HEIGHT) {
			graphics.setTooltipForNextFrame(minecraft.font, tooltip.get(), mouseX, mouseY);
		}
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return widgets();
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return widgets();
	}

	/** Everything in the row that can be clicked or focused, for both of the lists above. */
	protected abstract List<? extends AbstractWidget> widgets();
}

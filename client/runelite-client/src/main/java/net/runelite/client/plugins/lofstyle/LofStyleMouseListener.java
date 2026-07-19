/*
 * Fall of Varrock — Character Style window (mouse input). Arrows step the look/colour (the
 * in-world model updates live), gender buttons switch, DONE confirms and closes.
 */
package net.runelite.client.plugins.lofstyle;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofStyleMouseListener extends MouseAdapter
{
	private final LofStylePlugin plugin;
	private final LofStyleOverlay overlay;

	@Inject
	LofStyleMouseListener(LofStylePlugin plugin, LofStyleOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!overlay.isVisible() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofStyleOverlay.OUTSIDE)
		{
			return event;
		}
		if (hit == LofStyleOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofStyleOverlay.DONE)
		{
			plugin.sendDone();
			overlay.setVisible(false);
		}
		else if (hit == LofStyleOverlay.GENDER_MALE)
		{
			plugin.sendGender(false);
		}
		else if (hit == LofStyleOverlay.GENDER_FEMALE)
		{
			plugin.sendGender(true);
		}
		else if (hit >= LofStyleOverlay.ARROW_BASE)
		{
			final int idx = hit - LofStyleOverlay.ARROW_BASE;
			final int row = idx / 2;
			final int delta = (idx % 2) == 0 ? -1 : 1;
			if (row < LofStyleOverlay.ROWS.length && overlay.rowEnabled(row))
			{
				final int[] def = LofStyleOverlay.ROWS[row];
				plugin.sendStep(def[0] == 1, def[1], delta);
			}
		}
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	private MouseEvent swallowIfOnWindow(MouseEvent event)
	{
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofStyleOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

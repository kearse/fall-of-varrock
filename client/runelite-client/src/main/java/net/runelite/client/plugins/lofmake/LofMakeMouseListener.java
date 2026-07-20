/*
 * Fall of Varrock — the making window (mouse input). Rows select, chips set quantity, MAKE sends
 * the order (window stays open — the station keeps working); clicks on the window never fall
 * through to the world.
 */
package net.runelite.client.plugins.lofmake;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofMakeMouseListener extends MouseAdapter
{
	private final LofMakePlugin plugin;
	private final LofMakeOverlay overlay;

	@Inject
	LofMakeMouseListener(LofMakePlugin plugin, LofMakeOverlay overlay)
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
		if (hit == LofMakeOverlay.OUTSIDE)
		{
			return event;
		}
		if (hit == LofMakeOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofMakeOverlay.MAKE)
		{
			final LofMakeOverlay.Recipe r = overlay.selectedRecipe();
			final int qty = overlay.cachedQty(); // cached on the client thread — never read state here
			if (r != null && qty > 0)
			{
				plugin.sendMake(r.resultId, qty);
			}
		}
		else if (hit >= LofMakeOverlay.ROW_BASE)
		{
			overlay.setSelected(hit - LofMakeOverlay.ROW_BASE);
		}
		else if (hit >= LofMakeOverlay.QTY_BASE)
		{
			overlay.setQtyChoice(hit - LofMakeOverlay.QTY_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofMakeOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

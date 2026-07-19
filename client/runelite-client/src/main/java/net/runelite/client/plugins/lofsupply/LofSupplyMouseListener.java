/*
 * Fall of Varrock — Supply Depot window (mouse input). Tabs switch category, row chips sell
 * 1 / 5 / All of that item, the footer buttons hand in the tab or everything; the window stays
 * open (the server re-pushes the manifest). Clicks on the window never reach the world.
 */
package net.runelite.client.plugins.lofsupply;

import java.awt.event.MouseEvent;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofSupplyMouseListener extends MouseAdapter
{
	private final LofSupplyPlugin plugin;
	private final LofSupplyOverlay overlay;

	@Inject
	LofSupplyMouseListener(LofSupplyPlugin plugin, LofSupplyOverlay overlay)
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
		if (hit == LofSupplyOverlay.OUTSIDE)
		{
			return event;
		}
		if (hit == LofSupplyOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofSupplyOverlay.HAND_IN_TAB)
		{
			plugin.sendCategory(overlay.getActiveTab());
		}
		else if (hit == LofSupplyOverlay.HAND_IN_ALL)
		{
			plugin.sendAll();
		}
		else if (hit >= LofSupplyOverlay.CHIP_BASE)
		{
			final int idx = hit - LofSupplyOverlay.CHIP_BASE;
			final int row = idx / 3;
			final int chip = idx % 3;
			final List<LofSupplyOverlay.Entry> rows = overlay.carriedRows();
			if (row < rows.size())
			{
				final int qty = chip == 0 ? 1 : chip == 1 ? 5 : 0; // 0 = all
				plugin.sendItem(rows.get(row).id, qty);
			}
		}
		else if (hit >= LofSupplyOverlay.TAB_BASE)
		{
			overlay.setActiveTab(hit - LofSupplyOverlay.TAB_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofSupplyOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

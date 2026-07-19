/*
 * Fall of Varrock — Feudal Ranks window (mouse input).
 *
 * Hit-tests left-clicks against the open window: RANK UP sends the purchase (the window stays
 * open — the server re-pulses the varp and the ladder advances in place), the X closes. Clicks
 * anywhere on the window are consumed so they never fall through to the game world.
 */
package net.runelite.client.plugins.lofranks;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofRanksMouseListener extends MouseAdapter
{
	private final LofRanksPlugin plugin;
	private final LofRanksOverlay overlay;

	@Inject
	LofRanksMouseListener(LofRanksPlugin plugin, LofRanksOverlay overlay)
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
		if (hit == LofRanksOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofRanksOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofRanksOverlay.BUY)
		{
			final int next = overlay.nextRank();
			if (next >= 0)
			{
				plugin.sendBuy(next);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofRanksOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

/*
 * Fall of Varrock — Bond Exchange window (mouse input).
 */
package net.runelite.client.plugins.lofbonds;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofBondsMouseListener extends MouseAdapter
{
	private final LofBondsPlugin plugin;
	private final LofBondsOverlay overlay;

	@Inject
	LofBondsMouseListener(LofBondsPlugin plugin, LofBondsOverlay overlay)
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
		if (hit == LofBondsOverlay.OUTSIDE)
		{
			return event;
		}
		switch (hit)
		{
			case LofBondsOverlay.CLOSE:
				overlay.setVisible(false);
				break;
			case LofBondsOverlay.CLAIM:
				plugin.sendAction("claim");
				break;
			case LofBondsOverlay.MEMBER:
				plugin.sendAction("member");
				break;
			case LofBondsOverlay.DONOR:
				plugin.sendAction("donor");
				break;
			default:
				break;
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofBondsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

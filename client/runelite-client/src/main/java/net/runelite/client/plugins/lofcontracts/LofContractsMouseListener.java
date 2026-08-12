/*
 * Fall of Varrock — War Contracts window (mouse input).
 */
package net.runelite.client.plugins.lofcontracts;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofContractsMouseListener extends MouseAdapter
{
	private final LofContractsPlugin plugin;
	private final LofContractsOverlay overlay;

	@Inject
	LofContractsMouseListener(LofContractsPlugin plugin, LofContractsOverlay overlay)
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
		if (hit == LofContractsOverlay.OUTSIDE)
		{
			return event;
		}
		switch (hit)
		{
			case LofContractsOverlay.CLOSE:
				overlay.setVisible(false);
				break;
			case LofContractsOverlay.NEW_COMBAT:
				plugin.sendAction("combat");
				break;
			case LofContractsOverlay.NEW_RESOURCE:
				plugin.sendAction("resource");
				break;
			case LofContractsOverlay.REWARDS:
				plugin.sendAction("rewards");
				overlay.setVisible(false); // the native shop opens over the world
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
		// mousePressed() consumes only left-button presses, so only left releases/clicks may be
		// swallowed here: if the game saw a middle/right press but never its release, the engine's
		// camera drag stays live and the view keeps rotating with the mouse until the next click.
		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofContractsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

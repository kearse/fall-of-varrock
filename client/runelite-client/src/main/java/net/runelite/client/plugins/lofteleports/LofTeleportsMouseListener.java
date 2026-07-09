/*
 * Kingdom of Lumbridge — teleport portal overlay (mouse input).
 *
 * Hit-tests left-clicks against the open window: tabs switch category, a row sends the teleport
 * ("::tp <cat> <row>") and closes, the X closes. Clicks anywhere on the window are consumed so
 * they don't fall through to the game world.
 */
package net.runelite.client.plugins.lofteleports;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofTeleportsMouseListener extends MouseAdapter
{
	private final LofTeleportsPlugin plugin;
	private final LofTeleportsOverlay overlay;

	@Inject
	LofTeleportsMouseListener(LofTeleportsPlugin plugin, LofTeleportsOverlay overlay)
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
		if (hit == LofTeleportsOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofTeleportsOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit >= LofTeleportsOverlay.ROW_BASE)
		{
			plugin.sendTeleport(overlay.getActiveTab(), hit - LofTeleportsOverlay.ROW_BASE);
			overlay.setVisible(false);
		}
		else if (hit >= LofTeleportsOverlay.TAB_BASE)
		{
			overlay.setActiveTab(hit - LofTeleportsOverlay.TAB_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofTeleportsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

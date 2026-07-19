/*
 * Fall of Varrock — War Forge window (mouse input).
 */
package net.runelite.client.plugins.lofforge;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofForgeMouseListener extends MouseAdapter
{
	private final LofForgePlugin plugin;
	private final LofForgeOverlay overlay;

	@Inject
	LofForgeMouseListener(LofForgePlugin plugin, LofForgeOverlay overlay)
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
		if (hit == LofForgeOverlay.OUTSIDE)
		{
			return event;
		}
		if (hit == LofForgeOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofForgeOverlay.FORGE)
		{
			// Two-click confirm: forging consumes the base piece + untradeable Commendations.
			if (!overlay.isArmed())
			{
				overlay.setArmed(true);
			}
			else
			{
				final LofForgeOverlay.Recipe r = overlay.selectedRecipe();
				if (r != null)
				{
					plugin.sendForge(r.index);
				}
				overlay.setArmed(false);
			}
		}
		else if (hit >= LofForgeOverlay.ROW_BASE)
		{
			overlay.setSelected(hit - LofForgeOverlay.ROW_BASE);
		}
		else if (hit >= LofForgeOverlay.TAB_BASE)
		{
			overlay.setActiveTab(hit - LofForgeOverlay.TAB_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofForgeOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

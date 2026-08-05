/*
 * Fall of Varrock — Quest Journal window (mouse input).
 *
 * Hit-tests left-clicks against the open window: a track node switches the focused quest, the
 * Track button toggles the guidance arrow, Close / the ✕ dismiss. Clicks anywhere on the window
 * are consumed so they never fall through to the game world.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofQuestBookMouseListener extends MouseAdapter
{
	private final LofQuestsPlugin plugin;
	private final LofQuestBookOverlay overlay;

	@Inject
	LofQuestBookMouseListener(LofQuestsPlugin plugin, LofQuestBookOverlay overlay)
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
		if (hit == LofQuestBookOverlay.OUTSIDE)
		{
			return event; // clicks outside the window reach the game
		}

		if (hit == LofQuestBookOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofQuestBookOverlay.TRACK)
		{
			plugin.toggleQuestBookTrack();
		}
		else if (hit >= LofQuestBookOverlay.NODE_BASE)
		{
			plugin.focusQuestBook(hit - LofQuestBookOverlay.NODE_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofQuestBookOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

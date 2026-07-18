/*
 * Fall of Varrock — vote window (mouse input).
 *
 * Hit-tests left-clicks against the open window: a row opens that toplist's vote page in
 * the browser (the window stays open so the player can work through every site), the X
 * closes it. Any click on the window is consumed so it doesn't fall through to the game
 * world; a click outside the window closes it.
 */
package net.runelite.client.plugins.lofvote;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofVoteMouseListener extends MouseAdapter
{
	private final LofVotePlugin plugin;
	private final LofVoteOverlay overlay;

	@Inject
	LofVoteMouseListener(LofVotePlugin plugin, LofVoteOverlay overlay)
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
		if (hit == LofVoteOverlay.OUTSIDE)
		{
			// Click-away closes the window, and the click still reaches the game.
			plugin.close();
			return event;
		}

		if (hit == LofVoteOverlay.CLOSE)
		{
			plugin.close();
		}
		else if (hit >= LofVoteOverlay.ROW_BASE)
		{
			plugin.openSite(overlay.siteAt(hit - LofVoteOverlay.ROW_BASE));
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofVoteOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

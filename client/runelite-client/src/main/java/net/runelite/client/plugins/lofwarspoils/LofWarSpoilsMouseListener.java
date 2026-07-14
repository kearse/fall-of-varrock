/*
 * Fall of Varrock — Spoils of War window (mouse input).
 *
 * Hit-tests left-clicks against the open window: "Bank All" / "To Inventory" claim everything, a row
 * takes that one item to the inventory, the X closes it. Clicks on the window are consumed so they
 * don't fall through to the game world; a click outside closes it.
 */
package net.runelite.client.plugins.lofwarspoils;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofWarSpoilsMouseListener extends MouseAdapter
{
	private final LofWarSpoilsPlugin plugin;
	private final LofWarSpoilsOverlay overlay;

	@Inject
	LofWarSpoilsMouseListener(LofWarSpoilsPlugin plugin, LofWarSpoilsOverlay overlay)
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
		if (hit == LofWarSpoilsOverlay.OUTSIDE)
		{
			// Click-away closes the window; the click still reaches the game.
			plugin.close();
			return event;
		}

		if (hit == LofWarSpoilsOverlay.CLOSE)
		{
			plugin.close();
		}
		else if (hit == LofWarSpoilsOverlay.BANK_ALL)
		{
			plugin.bankAll();
		}
		else if (hit == LofWarSpoilsOverlay.TAKE_ALL)
		{
			plugin.takeAll();
		}
		else if (hit >= LofWarSpoilsOverlay.ROW_BASE)
		{
			plugin.take(hit - LofWarSpoilsOverlay.ROW_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofWarSpoilsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

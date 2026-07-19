/*
 * Fall of Varrock — Kit editor overlay (mouse input).
 *
 * Hit-tests left-clicks against the open kit editor window: palette items add, filled slots
 * remove, chips switch presets/kits/book/difficulty, tabs and bank pages stay client-side.
 * Every click on the window is consumed so it doesn't fall through to the game world.
 */
package net.runelite.client.plugins.lofkit;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofKitMouseListener extends MouseAdapter
{
	private final LofKitPlugin plugin;
	private final LofKitOverlay overlay;

	@Inject
	LofKitMouseListener(LofKitPlugin plugin, LofKitOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!overlay.isShowing() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofKitOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofKitOverlay.CLOSE)
		{
			plugin.sendAction("x");
		}
		else if (hit == LofKitOverlay.ACTION)
		{
			plugin.sendAction(overlay.isTraining() ? "start" : "load");
		}
		else if (hit == LofKitOverlay.PAGE_PREV)
		{
			overlay.pageDelta(-1);
		}
		else if (hit == LofKitOverlay.PAGE_NEXT)
		{
			overlay.pageDelta(1);
		}
		else if (hit >= LofKitOverlay.PAL_BASE)
		{
			final int id = overlay.palItemIdAt(hit - LofKitOverlay.PAL_BASE);
			if (id > 0)
			{
				plugin.sendAction("a " + id);
			}
		}
		else if (hit >= LofKitOverlay.TAB_BASE)
		{
			overlay.setTab(hit - LofKitOverlay.TAB_BASE);
		}
		else if (hit >= LofKitOverlay.DIFF_BASE)
		{
			plugin.sendAction("d " + (hit - LofKitOverlay.DIFF_BASE));
		}
		else if (hit >= LofKitOverlay.BOOK_BASE)
		{
			plugin.sendAction("b " + (hit - LofKitOverlay.BOOK_BASE));
		}
		else if (hit >= LofKitOverlay.KITSAVE_BASE)
		{
			plugin.sendAction("s " + (hit - LofKitOverlay.KITSAVE_BASE));
		}
		else if (hit >= LofKitOverlay.KITLOAD_BASE)
		{
			plugin.sendAction("k " + (hit - LofKitOverlay.KITLOAD_BASE));
		}
		else if (hit >= LofKitOverlay.PRESET_BASE)
		{
			plugin.sendAction("p " + (hit - LofKitOverlay.PRESET_BASE));
		}
		else if (hit >= LofKitOverlay.INV_BASE)
		{
			plugin.sendAction("ri " + (hit - LofKitOverlay.INV_BASE));
		}
		else if (hit >= LofKitOverlay.EQUIP_BASE)
		{
			plugin.sendAction("re " + (hit - LofKitOverlay.EQUIP_BASE));
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
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofKitOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

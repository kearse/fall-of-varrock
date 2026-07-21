/*
 * Fall of Varrock — Grand Exchange window (mouse input).
 *
 * Hit-tests left-clicks against the open board: the ✕ closes it, "Collect all" collects every slot,
 * an empty slot starts a new offer (native Buy/Sell → ::item search → number entry), an active slot's
 * body collects its proceeds and its small ✕ aborts it. Clicks on the window are consumed so they
 * don't fall through to the game; a click outside is left alone (the window only closes via its ✕, so
 * the native offer dialogs can open over it without dismissing it).
 */
package net.runelite.client.plugins.lofge;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofGeMouseListener extends MouseAdapter
{
	private final LofGePlugin plugin;
	private final LofGeOverlay overlay;

	@Inject
	LofGeMouseListener(LofGePlugin plugin, LofGeOverlay overlay)
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
		if (hit == LofGeOverlay.OUTSIDE)
		{
			return event; // leave click-away alone; only the ✕ closes
		}

		if (hit == LofGeOverlay.CLOSE)
		{
			plugin.close();
		}
		else if (hit == LofGeOverlay.COLLECT_ALL)
		{
			plugin.collectAll();
		}
		else if (hit >= LofGeOverlay.ABORT_BASE)
		{
			plugin.abort(hit - LofGeOverlay.ABORT_BASE);
		}
		else if (hit >= LofGeOverlay.SLOT_BASE)
		{
			final int box = hit - LofGeOverlay.SLOT_BASE;
			final LofGePlugin.Slot[] slots = plugin.getSlots();
			final LofGePlugin.Slot s = box >= 0 && box < slots.length ? slots[box] : null;
			if (s == null || s.isEmpty())
			{
				plugin.newOffer(box);
			}
			else
			{
				plugin.collect(box);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofGeOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

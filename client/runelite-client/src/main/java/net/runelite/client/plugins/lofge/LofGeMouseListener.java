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
		else if (plugin.getSetup() != null)
		{
			handleSetup(hit);
		}
		else
		{
			handleBoard(hit);
		}

		event.consume();
		return event;
	}

	private void handleSetup(int hit)
	{
		if (hit == LofGeOverlay.SET_TOGGLE)
		{
			plugin.setupToggleBuy();
		}
		else if (hit == LofGeOverlay.SET_QMINUS)
		{
			plugin.setupStepQty(-1);
		}
		else if (hit == LofGeOverlay.SET_QPLUS)
		{
			plugin.setupStepQty(1);
		}
		else if (hit == LofGeOverlay.SET_PMINUS)
		{
			plugin.setupStepPricePct(-5);
		}
		else if (hit == LofGeOverlay.SET_PPLUS)
		{
			plugin.setupStepPricePct(5);
		}
		else if (hit == LofGeOverlay.SET_PGUIDE)
		{
			plugin.setupPriceToGuide();
		}
		else if (hit == LofGeOverlay.SET_PCUSTOM)
		{
			plugin.promptPrice();
		}
		else if (hit == LofGeOverlay.SET_BACK)
		{
			plugin.setupBack();
		}
		else if (hit == LofGeOverlay.SET_CONFIRM)
		{
			plugin.setupConfirm();
		}
		else if (hit >= LofGeOverlay.SET_QPRESET_BASE)
		{
			switch (hit - LofGeOverlay.SET_QPRESET_BASE)
			{
				case 0:
					plugin.setupSetQty(1);
					break;
				case 1:
					plugin.setupSetQty(10);
					break;
				case 2:
					plugin.setupSetQty(100);
					break;
				case 3:
					plugin.setupSetQty(1000);
					break;
				default:
					plugin.promptQty(); // custom X
					break;
			}
		}
	}

	private void handleBoard(int hit)
	{
		if (hit == LofGeOverlay.COLLECT_ALL)
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
				plugin.openSetup(box);
			}
			else
			{
				plugin.collect(box);
			}
		}
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

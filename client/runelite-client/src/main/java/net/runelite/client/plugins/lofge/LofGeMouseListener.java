/*
 * Fall of Varrock — Grand Exchange window (mouse input).
 *
 * Hit-tests left-clicks against the open window. On the board: the ✕ closes it, "Collect all" collects
 * every slot, an empty card's Buy/Sell opens the offer screen, an active card's body collects and its
 * small ✕ aborts. On the offer screen: quantity/price steppers + presets, market listing rows (tap to
 * adopt a price), Back and Confirm. Clicks on the window are consumed so they don't fall through to the
 * game; a click outside is left alone — so the chat item-search (buy) and inventory right-click "Offer"
 * (sell) still work with the window open.
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
		if (!overlay.isVisible() || !SwingUtilities.isLeftMouseButton(event) || overlay.isGameMenuOpen())
		{
			return event; // a right-click menu is open — let its options (e.g. inventory "Offer") through
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
		// Market listing rows sit above every other setup code — check them first.
		if (hit >= LofGeOverlay.BID_ROW_BASE)
		{
			plugin.adoptMarketPrice(false, hit - LofGeOverlay.BID_ROW_BASE);
		}
		else if (hit >= LofGeOverlay.ASK_ROW_BASE)
		{
			plugin.adoptMarketPrice(true, hit - LofGeOverlay.ASK_ROW_BASE);
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
		// Check highest bases first — SLOT_SELL/BUY_BASE (400/300) sit above ABORT/SLOT_BASE.
		if (hit == LofGeOverlay.TAB_OFFERS)
		{
			plugin.openBoard();
		}
		else if (hit == LofGeOverlay.TAB_HISTORY)
		{
			plugin.openHistory();
		}
		else if (hit == LofGeOverlay.COLLECT_ALL)
		{
			plugin.collectAll();
		}
		else if (hit >= LofGeOverlay.SLOT_SELL_BASE)
		{
			plugin.openSetup(hit - LofGeOverlay.SLOT_SELL_BASE, false); // empty card → new sell
		}
		else if (hit >= LofGeOverlay.SLOT_BUY_BASE)
		{
			plugin.openSetup(hit - LofGeOverlay.SLOT_BUY_BASE, true); // empty card → new buy
		}
		else if (hit >= LofGeOverlay.ABORT_BASE)
		{
			plugin.abort(hit - LofGeOverlay.ABORT_BASE);
		}
		else if (hit >= LofGeOverlay.SLOT_BASE)
		{
			plugin.collect(hit - LofGeOverlay.SLOT_BASE); // occupied card body → collect
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
		// mousePressed() consumes only left-button presses, so only left releases/clicks may be
		// swallowed here: if the game saw a middle/right press but never its release, the engine's
		// camera drag stays live and the view keeps rotating with the mouse until the next click.
		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		if (overlay.isVisible() && !overlay.isGameMenuOpen() && overlay.hitTest(event.getPoint()) != LofGeOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

/*
 * Fall of Varrock — custom shop window (mouse input).
 *
 * Consumes only clicks that land on the window (tab, qty button, item, close, or the frame);
 * everything else falls through so the native inventory stays clickable for selling. Item clicks
 * buy the selected quantity; the rest drive the window. All actions go back as public-chat tokens
 * the server intercepts (see LofShopTabsPlugin / MessagePublicHandler).
 */
package net.runelite.client.plugins.lofshoptabs;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofShopTabsMouseListener extends MouseAdapter
{
	private final LofShopTabsPlugin plugin;
	private final LofShopTabsOverlay overlay;

	@Inject
	LofShopTabsMouseListener(LofShopTabsPlugin plugin, LofShopTabsOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!overlay.isShowing())
		{
			return event;
		}

		// If our right-click menu is open, ANY click resolves it: an option acts, anything else
		// dismisses. Takes priority over the rest of the window.
		if (overlay.isMenuOpen())
		{
			final int opt = overlay.menuHit(event.getPoint());
			if (opt >= 0)
			{
				plugin.menuAction(overlay.menuGridIndex(), opt);
			}
			overlay.closeMenu();
			event.consume();
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofShopTabsOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window (e.g. the inventory) reach the game
		}

		if (SwingUtilities.isRightMouseButton(event))
		{
			// Right-click an item → our own OSRS-style menu; elsewhere just swallow.
			if (hit >= LofShopTabsOverlay.ITEM_BASE)
			{
				overlay.openMenu(hit - LofShopTabsOverlay.ITEM_BASE, event.getX(), event.getY());
			}
			event.consume();
			return event;
		}

		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		if (hit == LofShopTabsOverlay.CLOSE)
		{
			plugin.closeShop();
		}
		else if (hit >= LofShopTabsOverlay.ITEM_BASE)
		{
			plugin.value(hit - LofShopTabsOverlay.ITEM_BASE); // left-click = Value (OSRS option 1)
		}
		else if (hit >= LofShopTabsOverlay.TAB_BASE)
		{
			plugin.selectTab(hit - LofShopTabsOverlay.TAB_BASE);
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

	/** Swallow any click (either button) that lands on the window or while our menu is open, so the
	 *  game never processes it behind us (no stray "Choose Option" / "Walk here"). */
	private MouseEvent swallowIfOnWindow(MouseEvent event)
	{
		if (overlay.isShowing()
			&& (overlay.isMenuOpen() || overlay.hitTest(event.getPoint()) != LofShopTabsOverlay.OUTSIDE))
		{
			event.consume();
		}
		return event;
	}
}

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
		// Only LEFT clicks act here. Right clicks are left alone so the game opens a menu, which
		// the plugin's onMenuOpened turns into the OSRS shop menu (Value + Buy 1/5/10/50).
		if (!overlay.isShowing() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofShopTabsOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window (e.g. the inventory) reach the game
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
		return swallowIfLeftOnWindow(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return swallowIfLeftOnWindow(event);
	}

	private MouseEvent swallowIfLeftOnWindow(MouseEvent event)
	{
		if (overlay.isShowing()
			&& SwingUtilities.isLeftMouseButton(event)
			&& overlay.hitTest(event.getPoint()) != LofShopTabsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

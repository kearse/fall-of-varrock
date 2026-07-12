/*
 * Fall of Varrock — storefront tab strip (mouse input).
 *
 * Only clicks that land ON a tab are consumed (and turned into "::shoptab <index>"); everything
 * else falls through so the native shop window — buy, sell, close, inventory — stays untouched.
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
		if (!overlay.isShowing() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofShopTabsOverlay.OUTSIDE)
		{
			return event;
		}
		plugin.selectTab(hit);
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return swallowIfOnTab(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return swallowIfOnTab(event);
	}

	private MouseEvent swallowIfOnTab(MouseEvent event)
	{
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofShopTabsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

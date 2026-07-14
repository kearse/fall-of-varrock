/*
 * Fall of Varrock — storefront tabs (Roat-style shop tabs).
 *
 * Vendors that own several stores used to funnel players through chat dialogue menus. Now the
 * server opens the shop window directly and streams the vendor's tab set; this plugin draws a
 * tab strip along the bottom of the open shop interface (group 300). Clicking a tab sends
 * "::shoptab <index>" as public chat, which the server intercepts (MessagePublicHandler →
 * shoptabclick → ShopTabs.switch) and re-opens the window on that store.
 *
 * Transport: identical to the command window (lofcommands). The server sends ordinary
 * GAME_MESSAGE chat lines prefixed "FOV_SHOP:", parsed the moment they ARRIVE (ChatMessage
 * event) and hidden from the chat box with a block-only "chatFilterCheck" hook. The lofcommands
 * hard-won rules apply here too — NEVER parse in chatFilterCheck (it re-runs over retained
 * history on every rebuild) and NEVER remove lines from the chat buffers (that wiped the chat).
 *
 * Wire format (each fits one line — labels are short):
 *   FOV_SHOP:clear                                        (every openShop — resets the strip)
 *   FOV_SHOP:tabs|<sel>|<label0>~<iconItemId0>|...        (a tabbed storefront just opened)
 *
 * Tabs render as REAL item sprites (the icon item id) in a row of small buttons floating above
 * the shop window — no panel, no shadow; the label only shows as a hover tooltip.
 */
package net.runelite.client.plugins.lofshoptabs;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Shop Tabs",
	description = "Tab strip on the shop window for vendors with several stores.",
	tags = {"lof", "shop", "store", "tabs", "vendor"},
	enabledByDefault = true
)
public class LofShopTabsPlugin extends Plugin
{
	/** Machine prefix the server tags storefront lines with (must match ShopTabs.PREFIX). */
	static final String PREFIX = "FOV_SHOP:";

	/** The standard OSRS shop interface group the server opens (see PlayerExt.openShop). */
	static final int SHOP_GROUP = 300;

	/** The shop stock grid child (server wires buy ops to it) — our anchor widget. */
	static final int STOCK_CHILD = 16;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofShopTabsOverlay overlay;

	@Inject
	private LofShopTabsConfig config;

	private LofShopTabsMouseListener mouseListener;

	/** Current tabs (empty = no tabbed storefront) + selected index. Client thread only. */
	private final List<Tab> tabs = new ArrayList<>();
	private int selected;

	/** One storefront tab: hover label + the item whose sprite is the button. */
	static final class Tab
	{
		final String label;
		final int itemId;

		Tab(String label, int itemId)
		{
			this.label = label;
			this.itemId = itemId;
		}
	}

	@Provides
	LofShopTabsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofShopTabsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofShopTabsMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (mouseListener != null)
		{
			mouseManager.unregisterMouseListener(mouseListener);
			mouseListener = null;
		}
		tabs.clear();
	}

	/** Parse storefront lines the moment they ARRIVE (fires exactly once per line). */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		final String message = event.getMessage();
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}

		final String body = message.substring(PREFIX.length());
		if (body.equals("clear"))
		{
			tabs.clear();
		}
		else if (body.startsWith("tabs|"))
		{
			// tabs|<selected>|<label0>~<iconItemId0>|<label1>~<iconItemId1>|...
			final String[] f = body.split("\\|");
			tabs.clear();
			if (f.length >= 3)
			{
				int sel = 0;
				try
				{
					sel = Integer.parseInt(f[1]);
				}
				catch (NumberFormatException ignored)
				{
				}
				for (int i = 2; i < f.length; i++)
				{
					if (f[i].isEmpty())
					{
						continue;
					}
					final int tilde = f[i].lastIndexOf('~');
					String label = f[i];
					int itemId = -1;
					if (tilde >= 0)
					{
						label = f[i].substring(0, tilde);
						try
						{
							itemId = Integer.parseInt(f[i].substring(tilde + 1));
						}
						catch (NumberFormatException ignored)
						{
						}
					}
					tabs.add(new Tab(label, itemId));
				}
				selected = Math.max(0, Math.min(sel, tabs.size() - 1));
			}
		}
		// Hide the just-arrived line immediately (the filter only runs on rebuilds otherwise).
		clientThread.invokeLater(client::refreshChat);
	}

	/** Block-only chat filter — hides our lines from every chatbox rebuild. NEVER parse here. */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"chatFilterCheck".equals(event.getEventName()))
		{
			return;
		}

		final String[] stringStack = client.getStringStack();
		final int stringStackSize = client.getStringStackSize();
		if (stringStackSize <= 0)
		{
			return;
		}

		final String message = stringStack[stringStackSize - 1];
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}

		final int[] intStack = client.getIntStack();
		final int intStackSize = client.getIntStackSize();
		intStack[intStackSize - 3] = 0;
	}

	/** Housekeeping: once the shop window is gone, the strip state is meaningless — drop it. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!tabs.isEmpty() && !isShopOpen())
		{
			tabs.clear();
		}
	}

	boolean isShopOpen()
	{
		final Widget stock = client.getWidget(SHOP_GROUP, STOCK_CHILD);
		return stock != null && !stock.isHidden();
	}

	/** Tab click: optimistic local select + tell the server to swap the store. */
	void selectTab(int index)
	{
		if (index < 0 || index >= tabs.size() || index == selected)
		{
			return;
		}
		selected = index;
		final String msg = "::shoptab " + index;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	boolean isEnabled()
	{
		return config.enabled();
	}

	List<Tab> getTabs()
	{
		return tabs;
	}

	int getSelected()
	{
		return selected;
	}
}

/*
 * Fall of Varrock — custom shop window (Roat-style, drawn client-side).
 *
 * Cache interfaces don't render on our client, so — like the teleport portal and stake screen —
 * we draw the whole shop ourselves: a themed window with an item-icon tab rail down the left, an
 * item grid with prices, a buy-quantity selector, and the player's balance in the header. It sits
 * over the native shop interface (group 300), which the server keeps open as the modal barrier and
 * for the inventory (selling stays native: right-click an inventory item).
 *
 * Transport: the server streams the stock as GAME_MESSAGE lines prefixed "FOV_SHOP:", parsed on
 * arrival (ChatMessage) and hidden from chat by a block-only "chatFilterCheck" hook. The two
 * lofcommands rules apply — never parse in chatFilterCheck, never remove chat lines.
 *
 * Wire format:
 *   FOV_SHOP:shop|<name>|<currencyLabel>|<balance>     (batch start; resets state)
 *   FOV_SHOP:item|<slot>|<itemId>|<qty>|<price>        (repeated)
 *   FOV_SHOP:shopend                                   (commit the grid)
 *   FOV_SHOP:tabs|<sel>|<label0>~<icon0>|<label1>~<icon1>|...   (tabbed vendors only)
 *   FOV_SHOP:clear                                     (shop fully closed)
 *
 * Clicks go back as public-chat tokens the server intercepts + suppresses:
 *   "::shoptab <i>"  "::shopbuy <slot> <amount>"  "::shopclose"
 */
package net.runelite.client.plugins.lofshoptabs;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
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
	name = "Fall of Varrock Shops",
	description = "Custom themed shop window with item-icon tabs, drawn client-side.",
	tags = {"lof", "shop", "store", "tabs", "vendor"},
	enabledByDefault = true
)
public class LofShopTabsPlugin extends Plugin
{
	static final String PREFIX = "FOV_SHOP:";

	/** The native shop interface the server keeps open as the modal barrier (see PlayerExt). */
	static final int SHOP_GROUP = 300;
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

	/** Live shop state (client thread only — no locking). */
	private String shopName = "";
	private String currencyLabel = "coins";
	private int balance;
	private final List<Item> items = new ArrayList<>();
	private final List<Tab> tabs = new ArrayList<>();
	private int selectedTab;

	/** Streaming buffer, filled between shop| and shopend. */
	private String bufName = "";
	private String bufLabel = "coins";
	private int bufBalance;
	private final List<Item> bufItems = new ArrayList<>();

	/** One stock line: the shop slot, the item + quantity, and the buy price. */
	static final class Item
	{
		final int slot;
		final int itemId;
		final int qty;
		final int price;

		Item(int slot, int itemId, int qty, int price)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.qty = qty;
			this.price = price;
		}
	}

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
		reset();
	}

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
		handle(message.substring(PREFIX.length()));
		clientThread.invokeLater(client::refreshChat);
	}

	private void handle(String body)
	{
		if (body.equals("clear"))
		{
			reset();
			return;
		}
		if (body.equals("shopend"))
		{
			shopName = bufName;
			currencyLabel = bufLabel;
			balance = bufBalance;
			items.clear();
			items.addAll(bufItems);
			return;
		}

		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "shop":
			{
				// shop|name|label|balance
				final String[] f = rest.split("\\|", 3);
				bufName = f.length > 0 ? f[0] : "";
				bufLabel = f.length > 1 ? f[1] : "coins";
				bufBalance = f.length > 2 ? parseInt(f[2], 0) : 0;
				bufItems.clear();
				break;
			}
			case "item":
			{
				// item|slot|id|qty|price
				final String[] f = rest.split("\\|");
				if (f.length >= 4)
				{
					bufItems.add(new Item(parseInt(f[0], 0), parseInt(f[1], -1), parseInt(f[2], 0), parseInt(f[3], 0)));
				}
				break;
			}
			case "tabs":
			{
				// tabs|sel|label~icon|label~icon|...
				final String[] f = rest.split("\\|");
				tabs.clear();
				if (f.length >= 2)
				{
					final int sel = parseInt(f[0], 0);
					for (int i = 1; i < f.length; i++)
					{
						if (f[i].isEmpty())
						{
							continue;
						}
						final int tilde = f[i].lastIndexOf('~');
						String label = f[i];
						int icon = -1;
						if (tilde >= 0)
						{
							label = f[i].substring(0, tilde);
							icon = parseInt(f[i].substring(tilde + 1), -1);
						}
						tabs.add(new Tab(label, icon));
					}
					selectedTab = Math.max(0, Math.min(sel, tabs.size() - 1));
				}
				break;
			}
			default:
				break;
		}
	}

	/** Ours — hide it from every chatbox rebuild (0 = block). Never parse here. */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"chatFilterCheck".equals(event.getEventName()))
		{
			return;
		}
		final String[] stringStack = client.getStringStack();
		final int size = client.getStringStackSize();
		if (size <= 0)
		{
			return;
		}
		final String message = stringStack[size - 1];
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}
		final int[] intStack = client.getIntStack();
		intStack[client.getIntStackSize() - 3] = 0;
	}

	/** Drop state once the shop window is gone — it's meaningless closed. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!items.isEmpty() && !isShopOpen())
		{
			reset();
		}
	}

	boolean isShopOpen()
	{
		final Widget stock = client.getWidget(SHOP_GROUP, STOCK_CHILD);
		return stock != null && !stock.isHidden();
	}

	void selectTab(int index)
	{
		if (index < 0 || index >= tabs.size() || index == selectedTab)
		{
			return;
		}
		selectedTab = index;
		send("::shoptab " + index);
	}

	/** Left-click an item (grid index): OSRS "Value" (option 1) — the server prints the price. */
	void value(int index)
	{
		if (index >= 0 && index < items.size())
		{
			send("::shopval " + items.get(index).slot);
		}
	}

	/** Buy from the right-click menu (Buy 1/5/10/50), by shop slot. */
	void buy(int slot, int amount)
	{
		send("::shopbuy " + slot + " " + amount);
	}

	void closeShop()
	{
		send("::shopclose");
	}

	/**
	 * Right-click over an item cell → the OSRS shop menu (Value + Buy 1/5/10/50), built with
	 * onClick handlers that send the matching command. Over any other part of the window we clear
	 * the menu so no world action ("Walk here") leaks through the overlay.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!overlay.isShowing())
		{
			return;
		}
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m == null)
		{
			return;
		}
		final int hit = overlay.hitTest(new java.awt.Point(m.getX(), m.getY()));
		if (hit == LofShopTabsOverlay.OUTSIDE)
		{
			return;
		}
		if (hit >= LofShopTabsOverlay.ITEM_BASE)
		{
			final int idx = hit - LofShopTabsOverlay.ITEM_BASE;
			if (idx >= items.size())
			{
				return;
			}
			final int slot = items.get(idx).slot;
			client.setMenuEntries(new MenuEntry[0]);
			addEntry("Buy 50", () -> buy(slot, 50));
			addEntry("Buy 10", () -> buy(slot, 10));
			addEntry("Buy 5", () -> buy(slot, 5));
			addEntry("Buy 1", () -> buy(slot, 1));
			addEntry("Value", () -> send("::shopval " + slot)); // last added = top entry
		}
		else
		{
			// Over the frame/tabs/close — suppress the default (world) menu.
			client.setMenuEntries(new MenuEntry[0]);
		}
	}

	private void addEntry(String option, Runnable action)
	{
		client.createMenuEntry(-1)
			.setOption(option)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> action.run());
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	private void reset()
	{
		shopName = "";
		items.clear();
		tabs.clear();
		selectedTab = 0;
	}

	private static int parseInt(String s, int def)
	{
		try
		{
			return Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	boolean isEnabled()
	{
		return config.enabled();
	}

	String getShopName()
	{
		return shopName;
	}

	String getCurrencyLabel()
	{
		return currencyLabel;
	}

	int getBalance()
	{
		return balance;
	}

	List<Item> getItems()
	{
		return items;
	}

	List<Tab> getTabs()
	{
		return tabs;
	}

	int getSelectedTab()
	{
		return selectedTab;
	}
}

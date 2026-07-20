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
 *   FOV_SHOP:shop|<name>|<currencyLabel>|<balance>|<mode>  (batch start; resets state; mode buy|sell)
 *   FOV_SHOP:item|<slot>|<itemId>|<qty>|<price>        (repeated)
 *   FOV_SHOP:shopend                                   (commit the grid)
 *   FOV_SHOP:tabs|<sel>|<label0>~<icon0>|<label1>~<icon1>|...   (tabbed vendors only)
 *   FOV_SHOP:bal|<balance>                             (header balance only — keeps the tab rail)
 *   FOV_SHOP:clear                                     (shop fully closed)
 *
 * SELL-ONLY stores (the Quartermaster's Supply Depot) send mode=sell: the shelf is a catalogue of
 * what the shop TAKES and each price is what it pays, so the right-click menu offers hand-ins
 * instead of buys. Selling from the inventory stays native either way (right-click an item).
 *
 * Clicks go back as public-chat tokens the server intercepts + suppresses:
 *   "::lofshoptab <i>"  "::lofshopbuy <slot> <amount>"  "::lofshopsell <slot> <amount|all>"
 *   "::lofshopclose"
 */
package net.runelite.client.plugins.lofshoptabs;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
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

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private LofShopTabsMouseListener mouseListener;
	private MouseWheelListener wheelListener;

	/** Live shop state (client thread only — no locking). */
	private String shopName = "";
	private String currencyLabel = "coins";
	private int balance;
	/** Sell-only storefront: the shelf is what the shop BUYS, priced at what it pays. */
	private boolean sellOnly;
	private final List<Item> items = new ArrayList<>();
	private final List<Tab> tabs = new ArrayList<>();
	private int selectedTab;

	/** Streaming buffer, filled between shop| and shopend. */
	private String bufName = "";
	private String bufLabel = "coins";
	private int bufBalance;
	private boolean bufSellOnly;
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
		wheelListener = event ->
		{
			if (overlay.handleScroll(event.getPoint(), event.getWheelRotation()))
			{
				event.consume();
			}
			return event;
		};
		mouseManager.registerMouseWheelListener(wheelListener);
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
		if (wheelListener != null)
		{
			mouseManager.unregisterMouseWheelListener(wheelListener);
			wheelListener = null;
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
			sellOnly = bufSellOnly;
			items.clear();
			items.addAll(bufItems);
			overlay.resetScroll();
			return;
		}

		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "shop":
			{
				// shop|name|label|balance|mode — a fresh store: drop any prior vendor's tabs (a tabbed
				// vendor re-sends its "tabs" line right after; a single-shop vendor sends none, so
				// its window correctly shows no rail instead of the previous vendor's stale tabs).
				final String[] f = rest.split("\\|", 4);
				bufName = f.length > 0 ? f[0] : "";
				bufLabel = f.length > 1 ? f[1] : "coins";
				bufBalance = f.length > 2 ? parseInt(f[2], 0) : 0;
				bufSellOnly = f.length > 3 && "sell".equals(f[3].trim());
				bufItems.clear();
				tabs.clear();
				selectedTab = 0;
				break;
			}
			case "bal":
			{
				// bal|balance — a hand-in changed the balance; the grid and tab rail stay untouched.
				balance = parseInt(rest, balance);
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
		send("::lofshoptab " + index);
	}

	/** Left-click an item (grid index): OSRS "Value" (option 1) — the server prints the price. */
	void value(int index)
	{
		if (index >= 0 && index < items.size())
		{
			send("::lofshopval " + items.get(index).slot);
		}
	}

	void closeShop()
	{
		send("::lofshopclose");
	}

	/** The item name for a grid cell (for the right-click menu), from the cache. */
	String itemName(int index)
	{
		if (index < 0 || index >= items.size())
		{
			return "";
		}
		try
		{
			return client.getItemDefinition(items.get(index).itemId).getName();
		}
		catch (Exception e)
		{
			return "";
		}
	}

	/**
	 * Act on a click in our own drawn right-click menu (see LofShopTabsOverlay.MENU_OPTS):
	 * Value / Buy 1 / Buy 10 / Buy 100 / Buy X / Examine / Cancel — or, at a sell-only store,
	 * Value / Hand in 1 / Hand in 10 / Hand in All / Hand in X / Examine / Cancel.
	 */
	void menuAction(int gridIndex, int opt)
	{
		if (gridIndex < 0 || gridIndex >= items.size())
		{
			return;
		}
		final int slot = items.get(gridIndex).slot;
		if (sellOnly)
		{
			switch (opt)
			{
				case 0: // Value
					send("::lofshopval " + slot);
					break;
				case 1: // Hand in 1
					send("::lofshopsell " + slot + " 1");
					break;
				case 2: // Hand in 10
					send("::lofshopsell " + slot + " 10");
					break;
				case 3: // Hand in All
					send("::lofshopsell " + slot + " all");
					break;
				case 4: // Hand in X — prompt for an amount
					promptAmount(slot, true);
					break;
				case 5: // Examine
					send("::lofshopexamine " + slot);
					break;
				default: // Cancel
					break;
			}
			return;
		}
		switch (opt)
		{
			case 0: // Value
				send("::lofshopval " + slot);
				break;
			case 1: // Buy 1
				send("::lofshopbuy " + slot + " 1");
				break;
			case 2: // Buy 10
				send("::lofshopbuy " + slot + " 10");
				break;
			case 3: // Buy 100
				send("::lofshopbuy " + slot + " 100");
				break;
			case 4: // Buy X — prompt for an amount
				promptAmount(slot, false);
				break;
			case 5: // Examine
				send("::lofshopexamine " + slot);
				break;
			default: // Cancel
				break;
		}
	}

	private void promptAmount(int slot, boolean sell)
	{
		clientThread.invokeLater(() ->
			chatboxPanelManager.openTextInput("Enter amount:")
				.onDone(input ->
				{
					try
					{
						final int amt = Integer.parseInt(input.trim().replaceAll("[^0-9]", ""));
						if (amt > 0)
						{
							send((sell ? "::lofshopsell " : "::lofshopbuy ") + slot + " " + amt);
						}
					}
					catch (NumberFormatException ignored)
					{
					}
				})
				.build());
	}

	/**
	 * Right-clicking the overlay must not leave the game's own menu ("Choose Option" / "Walk here")
	 * showing behind our window — we draw our own. Clear the game menu whenever the cursor is over
	 * the shop window.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!overlay.isShowing())
		{
			return;
		}
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m != null && overlay.hitTest(new java.awt.Point(m.getX(), m.getY())) != LofShopTabsOverlay.OUTSIDE)
		{
			client.setMenuEntries(new MenuEntry[0]);
		}
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	private void reset()
	{
		shopName = "";
		sellOnly = false;
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

	boolean isSellOnly()
	{
		return sellOnly;
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

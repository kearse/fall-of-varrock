/*
 * Fall of Varrock — Grand Exchange offer window (the lofge overlay).
 *
 * Draws our own player offer-book window in the shared lof style (LofTheme/LofModal), driven by the
 * server-side GE engine. It is the custom-client front-end for the offer book: the 8-slot board, the
 * collection box, and the offer-setup screen that places/collects/aborts offers.
 *
 * Choosing an item mirrors the real OSRS GE — the offer screen opens first and *stays up*, empty, until
 * an item is chosen:
 *   - Buy:  the native item search runs in the chatbox below the (still-open) window; picking a result
 *           streams the item back and fills the screen.
 *   - Sell: no search — while the empty sell screen is open, right-clicking an item in the inventory
 *           adds an "Offer" option (like selling to a store) that pulls the item into the screen.
 *
 * Transport (mirrors the shop window, FOV_SHOP): the server streams the board/setup as
 * ChatMessageType.GAMEMESSAGE lines prefixed "FOV_GE:", parsed on arrival (ChatMessage) and hidden
 * from the chat box by a block-only "chatFilterCheck" hook. The two hard rules apply — never parse in
 * chatFilterCheck, never remove chat lines.
 *
 * Wire format (one line each):
 *   FOV_GE:open                                                                (batch start; reset buffer)
 *   FOV_GE:slot|box|state|buy|item|price|qty|filled|collectCoins|collectItems  (8 lines; empty = state 0)
 *   FOV_GE:end                                                                 (commit + open the window)
 *   FOV_GE:bal|coins                                                           (coin readout)
 *   FOV_GE:setup|box|buy|item|guide|floor|ceil|bestAsk|bestBid|nSell|nBuy|own  (offer-setup header)
 *   FOV_GE:ask|price|qty   / FOV_GE:bid|price|qty                              (top market listings)
 *   FOV_GE:setupend                                                            (commit the setup view)
 *   FOV_GE:close                                                               (server-driven close)
 *
 * Clicks go back as public-chat tokens the server intercepts + suppresses:
 *   "::lofgesetup <box> <buy> [item]"  "::lofgeconfirm …"  "::lofgecollect <box|all>"
 *   "::lofgecancel <box>"  "::lofgeclose"
 */
package net.runelite.client.plugins.lofge;

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
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Grand Exchange",
	description = "Shows the custom Grand Exchange offer window when you talk to the GE clerk or type ::ge.",
	tags = {"lof", "ge", "grand", "exchange", "market", "trade"},
	enabledByDefault = true
)
public class LofGePlugin extends Plugin implements LofWindows.Window
{
	static final String PREFIX = "FOV_GE:";
	static final int SLOTS = 8;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofGeOverlay overlay;

	@Inject
	private LofGeConfig config;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private LofGeMouseListener mouseListener;

	/** Committed board (what the window draws) + the buffer filled while a batch streams in.
	 *  Both touched only on the client thread — no locking. */
	private final Slot[] slots = new Slot[SLOTS];
	private Slot[] buffer = new Slot[SLOTS];
	private long coins;

	/** One offer slot. state uses the server's GeState.wire (0 EMPTY .. 6 SOLD); buy = true for a buy offer. */
	static final class Slot
	{
		int state;
		boolean buy;
		int itemId;
		int price;
		int qty;
		int filled;
		long collectCoins;
		int collectItems;

		boolean isEmpty()
		{
			return state == 0 && itemId <= 0 && collectCoins == 0 && collectItems == 0;
		}
	}

	/** In-progress offer being composed in the drawn setup view (null = board view).
	 *  {@code item == 0} is the "screen open, no item chosen yet" state (buy: searching in chat; sell:
	 *  awaiting a right-click "Offer"). Once the item lands the server streams the rest and the player
	 *  edits quantity + price here; confirm creates the offer. */
	static final class Setup
	{
		final int box;
		final boolean buy;   // fixed by which button opened it (no in-screen toggle, like OSRS)
		int item;
		int guide;
		int floor;
		int ceil;
		int bestAsk = -1;
		int bestBid = -1;
		int nSell;
		int nBuy;
		int own;             // sell: how many the player holds (default + cap); buy: 0
		final List<int[]> asks = new ArrayList<>(); // {price, qty}, cheapest first
		final List<int[]> bids = new ArrayList<>(); // {price, qty}, dearest first
		int qty = 1;
		int price = 1;

		Setup(int box, boolean buy)
		{
			this.box = box;
			this.buy = buy;
		}

		boolean awaitingItem()
		{
			return item <= 0;
		}

		/** Highest sensible quantity: what you own for a sell, unbounded for a buy. */
		int maxQty()
		{
			return buy ? Integer.MAX_VALUE : Math.max(1, own);
		}

		/** Default price + quantity once the item + market snapshot have arrived. Buy takes the lowest
		 *  sell (best deal), sell the highest buy (instant sale); both fall back to the guide. */
		void applyDefaults()
		{
			final int def = buy ? (bestAsk > 0 ? bestAsk : guide) : (bestBid > 0 ? bestBid : guide);
			this.price = Math.max(1, def);
			this.qty = buy ? 1 : Math.max(1, own);
		}

		long total()
		{
			return (long) qty * price;
		}

		boolean banded()
		{
			return floor >= 0 && ceil >= 0;
		}
	}

	private Setup setup;        // non-null => the overlay draws the setup view for this offer
	private Setup pendingSetup; // being assembled from a setup/ask/bid/setupend batch

	@Provides
	LofGeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofGeConfig.class);
	}

	@Override
	protected void startUp()
	{
		for (int i = 0; i < SLOTS; i++)
		{
			slots[i] = new Slot();
		}
		overlayManager.add(overlay);
		mouseListener = new LofGeMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
		LofWindows.register(this);
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
		LofWindows.unregister(this);
		overlay.setVisible(false);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.CONSOLE)
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

	/** Display-only hiding of the machine lines. Pure filter — never parse or mutate buffers here. */
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
		intStack[client.getIntStackSize() - 3] = 0; // block from display
	}

	/** A foreign varp-driven window opening (kit editor / duel rules) dismisses our window too. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		LofWindows.onForeignSignal(event.getVarpId(), client.getVarpValue(event.getVarpId()));
	}

	/** While the empty *sell* screen is open, add an "Offer" option to inventory items — the player
	 *  right-clicks the item they want to sell (the same idiom as selling to a store), which pulls it
	 *  into the sell screen. Buy uses the chat search instead, so nothing is added there. */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (setup == null || setup.buy || !setup.awaitingItem())
		{
			return;
		}
		for (MenuEntry entry : event.getMenuEntries())
		{
			final Widget w = entry.getWidget();
			if (w == null || WidgetUtil.componentToInterface(w.getId()) != InterfaceID.INVENTORY)
			{
				continue;
			}
			final int itemId = w.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			final int box = setup.box;
			client.getMenu().createMenuEntry(-1)
				.setOption("Offer")
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> pickSellItem(box, itemId));
			return; // one item per menu; add the Offer once
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::gepanel opens a sample board locally (no server round-trip).
		if ("gepanel".equalsIgnoreCase(event.getCommand()))
		{
			handle("open");
			handle("slot|0|3|1|561|100|5000|3100|0|0");   // buy nature runes, 62%
			handle("slot|1|6|0|1333|15000|1|1|0|0");      // sold rune scimitar
			for (int i = 2; i < SLOTS; i++)
			{
				handle("slot|" + i + "|0|0|0|0|0|0|0|0");
			}
			handle("end");
			handle("bal|4213556");
			clientThread.invokeLater(client::refreshChat);
		}
		else if ("gesetuppanel".equalsIgnoreCase(event.getCommand()))
		{
			// Sample populated buy setup (nature rune: guide 100, store band 85-100, live market).
			handle("setup|0|1|561|100|85|100|98|92|7|4|0");
			handle("ask|98|3200");
			handle("ask|100|8000");
			handle("ask|105|12400");
			handle("bid|92|1500");
			handle("bid|90|5000");
			handle("bid|88|900");
			handle("setupend");
			clientThread.invokeLater(client::refreshChat);
		}
		else if ("gesetuppanel2".equalsIgnoreCase(event.getCommand()))
		{
			// Sample populated sell setup (abyssal whip: no store band, player-listed, you own 1).
			handle("setup|0|0|4151|60000|-1|-1|2050000|1850000|3|5|1");
			handle("ask|2050000|1");
			handle("ask|2100000|2");
			handle("bid|1850000|2");
			handle("bid|1800000|1");
			handle("bid|1750000|3");
			handle("setupend");
			clientThread.invokeLater(client::refreshChat);
		}
		else if ("gebuywait".equalsIgnoreCase(event.getCommand()))
		{
			// Preview the empty buy screen (awaiting the chat search).
			setup = new Setup(0, true);
			pendingSetup = null;
			overlay.setVisible(true);
			LofWindows.openExclusive(this);
		}
		else if ("gesellwait".equalsIgnoreCase(event.getCommand()))
		{
			// Preview the empty sell screen (awaiting a right-click "Offer").
			setup = new Setup(0, false);
			pendingSetup = null;
			overlay.setVisible(true);
			LofWindows.openExclusive(this);
		}
	}

	/** Parse one batch line (already stripped of {@link #PREFIX}). */
	private void handle(String body)
	{
		if (body.equals("open"))
		{
			buffer = new Slot[SLOTS];
			for (int i = 0; i < SLOTS; i++)
			{
				buffer[i] = new Slot();
			}
			setup = null; // a fresh board stream returns us to the board view
			pendingSetup = null;
			return;
		}
		if (body.equals("end"))
		{
			System.arraycopy(buffer, 0, slots, 0, SLOTS);
			overlay.setVisible(true);
			LofWindows.openExclusive(this);
			return;
		}
		if (body.equals("close"))
		{
			setup = null;
			pendingSetup = null;
			overlay.setVisible(false);
			return;
		}
		if (body.equals("setupend"))
		{
			if (pendingSetup != null)
			{
				pendingSetup.applyDefaults();
				setup = pendingSetup;
				pendingSetup = null;
				overlay.setVisible(true);
				LofWindows.openExclusive(this);
			}
			return;
		}

		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "slot":
			{
				// slot|box|state|buy|item|price|qty|filled|collectCoins|collectItems
				final String[] f = rest.split("\\|");
				if (f.length >= 9)
				{
					final int box = parseInt(f[0], -1);
					if (box >= 0 && box < SLOTS)
					{
						final Slot s = new Slot();
						s.state = parseInt(f[1], 0);
						s.buy = parseInt(f[2], 1) != 0;
						s.itemId = parseInt(f[3], 0);
						s.price = parseInt(f[4], 0);
						s.qty = parseInt(f[5], 0);
						s.filled = parseInt(f[6], 0);
						s.collectCoins = parseLong(f[7], 0);
						s.collectItems = parseInt(f[8], 0);
						buffer[box] = s;
					}
				}
				break;
			}
			case "bal":
			{
				coins = parseLong(rest, 0);
				break;
			}
			case "setup":
			{
				// setup|box|buy|item|guide|floor|ceil|bestAsk|bestBid|nSell|nBuy|own
				final String[] f = rest.split("\\|");
				if (f.length >= 6)
				{
					final Setup su = new Setup(parseInt(f[0], -1), parseInt(f[1], 1) != 0);
					su.item = parseInt(f[2], 0);
					su.guide = parseInt(f[3], 1);
					su.floor = parseInt(f[4], -1);
					su.ceil = parseInt(f[5], -1);
					if (f.length >= 11)
					{
						su.bestAsk = parseInt(f[6], -1);
						su.bestBid = parseInt(f[7], -1);
						su.nSell = parseInt(f[8], 0);
						su.nBuy = parseInt(f[9], 0);
						su.own = parseInt(f[10], 0);
					}
					pendingSetup = su;
				}
				break;
			}
			case "ask":
			case "bid":
			{
				// ask|price|qty  /  bid|price|qty
				final String[] f = rest.split("\\|");
				if (pendingSetup != null && f.length >= 2)
				{
					final int[] row = {parseInt(f[0], 0), parseInt(f[1], 0)};
					if (sub.equals("ask"))
					{
						pendingSetup.asks.add(row);
					}
					else
					{
						pendingSetup.bids.add(row);
					}
				}
				break;
			}
			default:
				break;
		}
	}

	// ---- board actions (client → server tokens) ------------------------------------------------

	/** Buy/Sell chosen on an empty card. The offer screen opens immediately and *stays visible* while
	 *  the item is chosen (buy: chat search; sell: right-click inventory "Offer"). */
	void openSetup(int box, boolean buy)
	{
		setup = new Setup(box, buy); // item 0 = awaiting the item choice
		pendingSetup = null;
		overlay.setVisible(true);
		if (buy)
		{
			// Kick off the native item search — it renders in the chatbox below the still-open window.
			send("::lofgesetup " + box + " 1");
		}
		// Sell path: no token yet; onMenuOpened adds "Offer" to inventory items → pickSellItem.
	}

	/** Sell: pull the right-clicked inventory item into the open sell screen (server validates + streams
	 *  the populated setup back). */
	void pickSellItem(int box, int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		send("::lofgesetup " + box + " 0 " + itemId);
	}

	void collect(int box)
	{
		send("::lofgecollect " + box);
	}

	void collectAll()
	{
		send("::lofgecollect all");
	}

	void abort(int box)
	{
		send("::lofgecancel " + box);
	}

	/** Close: tell the server (so it stops streaming) and hide locally. */
	void close()
	{
		send("::lofgeclose");
		setup = null;
		pendingSetup = null;
		overlay.setVisible(false);
	}

	// ---- setup-view edits (all client-side until Confirm) --------------------------------------

	Setup getSetup()
	{
		return setup;
	}

	void setupStepQty(int delta)
	{
		if (setup != null && !setup.awaitingItem())
		{
			setup.qty = clampQty(setup.qty + delta);
		}
	}

	void setupSetQty(int qty)
	{
		if (setup != null && !setup.awaitingItem())
		{
			setup.qty = clampQty(qty);
		}
	}

	private int clampQty(int qty)
	{
		return Math.max(1, Math.min(qty, setup.maxQty()));
	}

	void setupStepPricePct(int pct)
	{
		if (setup != null && !setup.awaitingItem())
		{
			final long next = Math.round(setup.price * (1.0 + pct / 100.0));
			// ensure ±1 movement even on tiny prices
			final long bumped = pct > 0 ? Math.max(next, setup.price + 1L) : Math.min(next, setup.price - 1L);
			setup.price = (int) Math.max(1, Math.min(Integer.MAX_VALUE, bumped));
		}
	}

	void setupPriceToGuide()
	{
		if (setup != null && !setup.awaitingItem())
		{
			setup.price = Math.max(1, setup.guide);
		}
	}

	void setupSetPrice(int price)
	{
		if (setup != null && !setup.awaitingItem())
		{
			setup.price = Math.max(1, price);
		}
	}

	/** Click-to-adopt a market listing's price (ask = a Selling row, bid = a Buying row). */
	void adoptMarketPrice(boolean ask, int i)
	{
		if (setup == null || setup.awaitingItem())
		{
			return;
		}
		final List<int[]> rows = ask ? setup.asks : setup.bids;
		if (i >= 0 && i < rows.size())
		{
			setupSetPrice(rows.get(i)[0]);
		}
	}

	/** Custom numeric entry via the chatbox (same pattern as the shop window). */
	void promptQty()
	{
		if (setup == null || setup.awaitingItem())
		{
			return;
		}
		clientThread.invokeLater(() ->
			chatboxPanelManager.openTextInput("Quantity:")
				.onDone((String input) ->
				{
					setupSetQty(parseAmount(input));
				})
				.build());
	}

	void promptPrice()
	{
		if (setup == null || setup.awaitingItem())
		{
			return;
		}
		clientThread.invokeLater(() ->
			chatboxPanelManager.openTextInput("Price per item:")
				.onDone((String input) ->
				{
					setupSetPrice(parseAmount(input));
				})
				.build());
	}

	/** Back from the setup view to the board (local — the board data is still cached). */
	void setupBack()
	{
		setup = null;
		pendingSetup = null;
	}

	/** Place the offer with the composed values, then let the server re-stream the board. */
	void setupConfirm()
	{
		final Setup s = setup;
		if (s == null || s.awaitingItem() || s.qty <= 0 || s.price <= 0)
		{
			return;
		}
		send("::lofgeconfirm " + s.box + " " + (s.buy ? 1 : 0) + " " + s.item + " " + s.price + " " + s.qty);
		setup = null;
		pendingSetup = null;
	}

	private static int parseAmount(String input)
	{
		try
		{
			return Integer.parseInt(input.trim().replaceAll("[^0-9]", ""));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	// ---- LofWindows.Window -----------------------------------------------------------------------

	@Override
	public boolean isWindowVisible()
	{
		return overlay.isVisible();
	}

	@Override
	public void hideWindow()
	{
		overlay.setVisible(false);
	}

	// ---- accessors for the overlay ---------------------------------------------------------------

	boolean isEnabled()
	{
		return config.enabled();
	}

	Slot[] getSlots()
	{
		return slots;
	}

	long getCoins()
	{
		return coins;
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

	private static long parseLong(String s, long def)
	{
		try
		{
			return Long.parseLong(s.trim());
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}
}

/*
 * Fall of Varrock — Grand Exchange offer window (the lofge overlay).
 *
 * Draws our own player offer-book window in the shared lof style (LofTheme/LofModal), driven by the
 * server-side GE engine. It is the custom-client front-end for the offer book: the 8-slot board, the
 * collection box, and the buttons that place/collect/abort offers. Opening an offer uses the native
 * prompts (Buy/Sell dialog → the ::item search → number entry) — reliable and already working — so
 * this window just paints the board and routes clicks.
 *
 * Transport (mirrors the shop window, FOV_SHOP): the server streams the board as
 * ChatMessageType.GAMEMESSAGE lines prefixed "FOV_GE:", parsed on arrival (ChatMessage) and hidden
 * from the chat box by a block-only "chatFilterCheck" hook. The two hard rules apply — never parse in
 * chatFilterCheck, never remove chat lines.
 *
 * Wire format (one line each):
 *   FOV_GE:open                                                                (batch start; reset buffer)
 *   FOV_GE:slot|box|state|buy|item|price|qty|filled|collectCoins|collectItems  (8 lines; empty = state 0)
 *   FOV_GE:end                                                                 (commit + open the window)
 *   FOV_GE:bal|coins                                                           (coin readout)
 *   FOV_GE:close                                                               (server-driven close)
 *
 * Clicks go back as public-chat tokens the server intercepts + suppresses:
 *   "::lofgenew <box>"  "::lofgecollect <box|all>"  "::lofgecancel <box>"  "::lofgeclose"
 */
package net.runelite.client.plugins.lofge;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.VarbitChanged;
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

	/** In-progress offer being composed in the drawn setup view (null = board view). Owned client-side:
	 *  the server supplies box/item/guide/band, the player edits buy/qty/price here, confirm creates it. */
	static final class Setup
	{
		final int box;
		final int item;
		final int guide;
		final int floor;
		final int ceil;
		boolean buy = true;
		int qty = 1;
		int price;

		Setup(int box, int item, int guide, int floor, int ceil)
		{
			this.box = box;
			this.item = item;
			this.guide = guide;
			this.floor = floor;
			this.ceil = ceil;
			this.price = Math.max(1, guide);
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

	private Setup setup; // non-null => the overlay draws the setup view for this offer

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
			// Sample setup view for a commodity (nature rune: guide 100, store band 85-100).
			handle("setup|0|561|100|85|100");
			clientThread.invokeLater(client::refreshChat);
		}
		else if ("gesetuppanel2".equalsIgnoreCase(event.getCommand()))
		{
			// Sample setup view for a player-listed item (abyssal whip: no store band).
			handle("setup|0|4151|60000|-1|-1");
			clientThread.invokeLater(client::refreshChat);
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
			overlay.setVisible(false);
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
				// setup|box|item|guide|floor|ceil
				final String[] f = rest.split("\\|");
				if (f.length >= 5)
				{
					setup = new Setup(parseInt(f[0], -1), parseInt(f[1], 0), parseInt(f[2], 1),
						parseInt(f[3], -1), parseInt(f[4], -1));
					overlay.setVisible(true);
					LofWindows.openExclusive(this);
				}
				break;
			}
			default:
				break;
		}
	}

	// ---- board actions (client → server tokens) ------------------------------------------------

	/** Empty slot: ask the server to run the native item search, then stream the setup view back. */
	void openSetup(int box)
	{
		send("::lofgesetup " + box);
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
		overlay.setVisible(false);
	}

	// ---- setup-view edits (all client-side until Confirm) --------------------------------------

	Setup getSetup()
	{
		return setup;
	}

	void setupToggleBuy()
	{
		if (setup != null)
		{
			setup.buy = !setup.buy;
		}
	}

	void setupStepQty(int delta)
	{
		if (setup != null)
		{
			setup.qty = Math.max(1, setup.qty + delta);
		}
	}

	void setupSetQty(int qty)
	{
		if (setup != null)
		{
			setup.qty = Math.max(1, qty);
		}
	}

	void setupStepPricePct(int pct)
	{
		if (setup != null)
		{
			final long next = Math.round(setup.price * (1.0 + pct / 100.0));
			setup.price = (int) Math.max(1, Math.min(Integer.MAX_VALUE, next));
		}
	}

	void setupPriceToGuide()
	{
		if (setup != null)
		{
			setup.price = Math.max(1, setup.guide);
		}
	}

	void setupSetPrice(int price)
	{
		if (setup != null)
		{
			setup.price = Math.max(1, price);
		}
	}

	/** Custom numeric entry via the chatbox (same pattern as the shop window). */
	void promptQty()
	{
		clientThread.invokeLater(() ->
			chatboxPanelManager.openTextInput("Quantity:")
				.onDone(input -> setupSetQty(parseAmount(input)))
				.build());
	}

	void promptPrice()
	{
		clientThread.invokeLater(() ->
			chatboxPanelManager.openTextInput("Price per item:")
				.onDone(input -> setupSetPrice(parseAmount(input)))
				.build());
	}

	/** Back from the setup view to the board (local — the board data is still cached). */
	void setupBack()
	{
		setup = null;
	}

	/** Place the offer with the composed values, then let the server re-stream the board. */
	void setupConfirm()
	{
		final Setup s = setup;
		if (s == null || s.qty <= 0 || s.price <= 0)
		{
			return;
		}
		send("::lofgeconfirm " + s.box + " " + (s.buy ? 1 : 0) + " " + s.item + " " + s.price + " " + s.qty);
		setup = null;
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

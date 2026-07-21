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
				// Reserved for a future drawn setup box; v1 creates offers via native prompts.
				break;
			default:
				break;
		}
	}

	// ---- actions (client → server tokens) ------------------------------------------------------

	void newOffer(int box)
	{
		send("::lofgenew " + box);
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
		overlay.setVisible(false);
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

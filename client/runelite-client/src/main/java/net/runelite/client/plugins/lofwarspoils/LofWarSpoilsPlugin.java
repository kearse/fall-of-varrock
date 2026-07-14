/*
 * Fall of Varrock — Spoils of War claim window.
 *
 * When a Minister/King wins a campaign or conquest, the server holds their commander cut (war-stake
 * return + tithe, plus a rare 3rd age relic) in a claim stash instead of auto-banking it. Typing
 * ::claim streams the pending loot to this plugin, which draws a themed window listing the items with
 * "Bank All" / "To Inventory" buttons (and per-item click). A short on-screen toast also fires the
 * moment a war is won, prompting the commander to ::claim.
 *
 * Transport (mirrors the command window — the eviction-safe recipe): the server streams the list as
 * ChatMessageType.CONSOLE lines prefixed "FOV_SPOILS:", parsed on arrival (ChatMessage) and hidden
 * from the chat box by a block-only "chatFilterCheck" hook. The two hard rules apply — never parse in
 * chatFilterCheck, never remove chat lines.
 *
 * Wire format (one line each):
 *   FOV_SPOILS:open                          (batch start; resets the buffer)
 *   FOV_SPOILS:item|&lt;index&gt;|&lt;itemId&gt;|&lt;qty&gt;    (repeated)
 *   FOV_SPOILS:end                           (commit + open the window)
 *   FOV_SPOILS:toast|&lt;text&gt;                  (timed on-screen banner)
 *   FOV_SPOILS:close                         (server-driven close — the stash emptied)
 *
 * Clicks go back as public-chat tokens the server intercepts + suppresses:
 *   "::spoils bankall"  "::spoils takeall"  "::spoils take &lt;index&gt;"
 */
package net.runelite.client.plugins.lofwarspoils;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock War Spoils",
	description = "Shows a themed claim window for a war commander's spoils when you type ::claim.",
	tags = {"lof", "war", "spoils", "claim", "loot"},
	enabledByDefault = true
)
public class LofWarSpoilsPlugin extends Plugin
{
	static final String PREFIX = "FOV_SPOILS:";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofWarSpoilsOverlay overlay;

	@Inject
	private LofWarSpoilsConfig config;

	private LofWarSpoilsMouseListener mouseListener;
	private MouseWheelListener wheelListener;

	/** Committed item list (what the window draws) + the buffer filled while a batch streams in.
	 *  Both touched only on the client thread — no locking. */
	private final List<Item> items = new ArrayList<>();
	private final List<Item> buffer = new ArrayList<>();

	/** Toast banner text + wall-clock expiry (0 = no banner). */
	private String toastText = "";
	private long toastExpiresAt;

	/** One pending spoil: the item and its quantity. */
	static final class Item
	{
		final int itemId;
		final int qty;

		Item(int itemId, int qty)
		{
			this.itemId = itemId;
			this.qty = qty;
		}
	}

	@Provides
	LofWarSpoilsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofWarSpoilsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofWarSpoilsMouseListener(this, overlay);
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
		overlay.setVisible(false);
		items.clear();
		buffer.clear();
		toastExpiresAt = 0L;
	}

	/** Consume a spoils line the moment it arrives. CONSOLE is the transport channel (own line
	 *  buffer, never evicts game messages); GAMEMESSAGE is accepted too for back-compat. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CONSOLE && event.getType() != ChatMessageType.GAMEMESSAGE)
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

	/** Display-only hiding of the machine lines (this client renders CONSOLE in the chat box).
	 *  Pure filter — never parse or mutate buffers here. */
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

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::spoilspanel opens a sample window locally (no server round-trip).
		if ("spoilspanel".equalsIgnoreCase(event.getCommand()))
		{
			handle("open");
			handle("item|0|995|5000000");
			handle("item|1|1127|1");   // rune platebody (stand-in)
			handle("item|2|4151|1");   // abyssal whip (stand-in)
			handle("end");
			handle("toast|Spoils of Varrock await — ::claim");
			clientThread.invokeLater(client::refreshChat);
		}
	}

	/** Parse one batch line (already stripped of the {@link #PREFIX}). */
	private void handle(String body)
	{
		if (body.equals("open"))
		{
			buffer.clear();
			return;
		}
		if (body.equals("end"))
		{
			items.clear();
			items.addAll(buffer);
			if (!items.isEmpty())
			{
				overlay.resetScroll();
				overlay.setVisible(true);
			}
			return;
		}
		if (body.equals("close"))
		{
			items.clear();
			overlay.setVisible(false);
			return;
		}

		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "item":
			{
				// item|index|id|qty
				final String[] f = rest.split("\\|");
				if (f.length >= 3)
				{
					buffer.add(new Item(parseInt(f[1], -1), parseInt(f[2], 0)));
				}
				break;
			}
			case "toast":
			{
				toastText = rest;
				toastExpiresAt = System.currentTimeMillis() + TOAST_MS;
				break;
			}
			default:
				break;
		}
	}

	/** Dismiss the window (X / click-away). Purely local — the stash stays server-side until claimed. */
	void close()
	{
		overlay.setVisible(false);
	}

	void bankAll()
	{
		send("::spoils bankall");
	}

	void takeAll()
	{
		send("::spoils takeall");
	}

	void take(int index)
	{
		send("::spoils take " + index);
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
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

	List<Item> getItems()
	{
		return items;
	}

	/** The toast text while its banner should show, else null. */
	String activeToast()
	{
		if (toastExpiresAt > 0L && System.currentTimeMillis() < toastExpiresAt && !toastText.isEmpty())
		{
			return toastText;
		}
		return null;
	}

	private static final long TOAST_MS = 6000L;
}

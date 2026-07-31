/*
 * Fall of Varrock — server announcement ticker.
 *
 * Roat-style broadcast system: curated server headlines (boss spawns, campaign captures, events,
 * votes) render as coloured lines stacked directly ABOVE the chat box — they do NOT appear inside
 * the chat itself. The server sends them as ChatMessageType.BROADCAST chat lines (the server-side
 * Announce helper / ::announce, ::broadcast); this plugin collects them for the overlay and hides
 * the raw lines from the chatbox with a block-only "chatFilterCheck" hook + one forced
 * refreshChat() — the exact mechanism the stock Chat Filter plugin (and our lofcommands window)
 * uses, which never disturbs the rest of the chat.
 *
 * Hard-won rules inherited from lofcommands (do NOT regress them):
 *   1. NEVER parse/collect in chatFilterCheck — it re-runs over the whole retained chat history on
 *      every chatbox rebuild. Collection happens in onChatMessage (fires exactly once per line);
 *      chatFilterCheck only ever sets the block flag.
 *   2. NEVER remove lines from the chat buffers (removeMessageNode wiped the player's chat). The
 *      blocked lines stay harmlessly in history, hidden by the filter on every rebuild.
 *
 * Local test without the server: type ::testannounce to inject sample headlines.
 */
package net.runelite.client.plugins.lofannouncements;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.lofintro.LofIntroPlugin;
import net.runelite.client.plugins.lofsupplydrop.LofSupplyDropPlugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Fall of Varrock Announcements",
	description = "Server broadcasts as a roat-style ticker above the chat box instead of chat lines.",
	tags = {"lof", "announcement", "broadcast", "ticker", "boss", "campaign"},
	enabledByDefault = true
)
public class LofAnnouncementsPlugin extends Plugin
{
	/** Hard cap on retained announcements (older ones drop off regardless of hold time). */
	private static final int CAP = 12;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofAnnouncementsOverlay overlay;

	@Inject
	private LofAnnouncementsConfig config;

	/** Oldest first (new headlines append at the tail, roat-style). Both the chat callback and
	 *  the overlay run on the client thread; the concurrent deque keeps it safe regardless. */
	private final Deque<Announcement> announcements = new ConcurrentLinkedDeque<>();

	@Provides
	LofAnnouncementsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofAnnouncementsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		announcements.clear();
		// Un-hide broadcast lines in the chatbox now that the ticker is gone.
		clientThread.invokeLater(client::refreshChat);
	}

	/** Collect the headline the moment it ARRIVES (fires exactly once per line). */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.BROADCAST)
		{
			return;
		}

		// Keep the RAW message (with <col> tags) so the overlay can honour the server's colour;
		// only the visible text matters for the empty check.
		final String raw = event.getMessage();
		final String visible = Text.removeTags(raw).trim();

		// Machine-to-machine triggers (intro video, supply-drop map markers) are not headlines
		// (still hidden from the chatbox by the filter below — they were never meant to be seen).
		if (!visible.isEmpty() && !visible.startsWith(LofIntroPlugin.TRIGGER_PREFIX)
			&& !visible.startsWith(LofSupplyDropPlugin.TRIGGER_PREFIX))
		{
			final int lifetime = Math.max(5, config.lifetimeSeconds());
			announcements.addLast(new Announcement(raw, System.currentTimeMillis() + lifetime * 1000L));

			while (announcements.size() > CAP)
			{
				announcements.removeFirst();
			}
		}

		// The chat add path doesn't run chatFilterCheck on this client — force one rebuild so the
		// just-arrived raw line is hidden immediately instead of on the next natural rebuild.
		clientThread.invokeLater(client::refreshChat);
	}

	/**
	 * Hide EVERY broadcast line from the chatbox — the ticker is their home now. Block-only, no
	 * parsing (rule 1); the lines stay in history, hidden on every rebuild (rule 2). Stack layout
	 * matches the stock ChatFilterPlugin: string top = message, ints [.., blocked, type, nodeId].
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"chatFilterCheck".equals(event.getEventName()) || !config.hideFromChat())
		{
			return;
		}

		final int[] intStack = client.getIntStack();
		final int intStackSize = client.getIntStackSize();
		if (intStackSize < 3)
		{
			return;
		}

		if (ChatMessageType.of(intStack[intStackSize - 2]) == ChatMessageType.BROADCAST)
		{
			intStack[intStackSize - 3] = 0; // 0 = block
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::testannounce injects sample headlines locally (no server round-trip).
		if ("testannounce".equalsIgnoreCase(event.getCommand()))
		{
			final long now = System.currentTimeMillis();
			final int lifetime = Math.max(5, config.lifetimeSeconds());
			announcements.addLast(new Announcement(
				"<col=ff4f4f>A Bloodlust is running at level 26 wilderness! Ends in 5:00.</col>", now + lifetime * 1000L));
			announcements.addLast(new Announcement(
				"<col=48c8f0>A 5K PKP MAX LMS game will start in 0:33. Join at ::lms!</col>", now + lifetime * 1000L));
			announcements.addLast(new Announcement(
				"<col=ffcc00>50M in PvP + PvM Bounties! ::bossbounties to track kills!</col>", now + lifetime * 1000L));
			while (announcements.size() > CAP)
			{
				announcements.removeFirst();
			}
		}
	}

	/**
	 * @return the texts to render right now — the most recent non-expired announcements in arrival
	 *         order (oldest first), capped at the configured line count.
	 */
	List<String> getLines()
	{
		final long now = System.currentTimeMillis();
		announcements.removeIf(a -> a.expiresAt <= now);

		final int max = Math.max(1, config.maxLines());
		final List<String> lines = new ArrayList<>(max);
		for (Announcement a : announcements)
		{
			lines.add(a.text);
		}
		// Keep only the newest `max`, preserving oldest-first order among them.
		while (lines.size() > max)
		{
			lines.remove(0);
		}
		return lines;
	}

	boolean isEnabled()
	{
		return config.enabled();
	}

	int fontSize()
	{
		return Math.max(8, config.fontSize());
	}

	private static final class Announcement
	{
		private final String text;
		private final long expiresAt;

		private Announcement(String text, long expiresAt)
		{
			this.text = text;
			this.expiresAt = expiresAt;
		}
	}
}

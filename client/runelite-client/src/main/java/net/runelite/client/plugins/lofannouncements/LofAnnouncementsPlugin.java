/*
 * Kingdom of Lumbridge — server announcement ticker.
 *
 * Surfaces curated server headlines (boss spawns, campaign captures, events, votes) in a
 * roak-style panel above the chat box. The server sends these as ChatMessageType.BROADCAST
 * chat lines (see the server-side Announce helper / ::announce, ::broadcast); this plugin
 * collects the recent ones and the overlay renders them. No custom packets — it just listens
 * to the broadcast chat channel.
 */
package net.runelite.client.plugins.lofannouncements;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Kingdom of Lumbridge Announcements",
	description = "Server announcement ticker above the chat box (boss spawns, campaigns, events).",
	tags = {"lof", "announcement", "broadcast", "ticker", "boss", "campaign"},
	enabledByDefault = true
)
public class LofAnnouncementsPlugin extends Plugin
{
	/** Hard cap on retained announcements (older ones drop off regardless of hold time). */
	private static final int CAP = 12;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofAnnouncementsOverlay overlay;

	@Inject
	private LofAnnouncementsConfig config;

	/** Newest first. Both the chat callback and the overlay run on the client thread; the
	 *  concurrent deque keeps it safe regardless. */
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
	}

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
		if (Text.removeTags(raw).trim().isEmpty())
		{
			return;
		}

		final int lifetime = Math.max(1, config.lifetimeSeconds());
		announcements.addFirst(new Announcement(raw, System.currentTimeMillis() + lifetime * 1000L));

		while (announcements.size() > CAP)
		{
			announcements.removeLast();
		}
	}

	/**
	 * @return the texts to render right now — the most recent non-expired announcements,
	 *         newest first, capped at the configured line count.
	 */
	List<String> getLines()
	{
		final long now = System.currentTimeMillis();
		announcements.removeIf(a -> a.expiresAt <= now);

		final int max = Math.max(1, config.maxLines());
		final List<String> lines = new ArrayList<>(max);
		for (Announcement a : announcements)
		{
			if (lines.size() >= max)
			{
				break;
			}
			lines.add(a.text);
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

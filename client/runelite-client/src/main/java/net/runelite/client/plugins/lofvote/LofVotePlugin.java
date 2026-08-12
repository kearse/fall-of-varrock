/*
 * Fall of Varrock — vote window.
 *
 * Typing ::vote makes the server stream the toplist sites as CONSOLE chat lines (see the
 * server-side DailyVotePlugin) and this plugin renders them as a clickable window styled
 * like the commands panel: one row per toplist, click a row and that site's vote page —
 * with the player's login already attached — opens in the default browser. The window
 * stays open so a player can click through every site in one sitting. Per-site cooldown
 * state rides along with each row so "ready" vs "come back in 3h" is visible at a glance.
 *
 * Transport (same hard-won CONSOLE rules as lofcommands — arrival-parse once in
 * ChatMessage, display-hide with a block-only chatFilterCheck, never touch the buffers):
 *   FOV_VOTE:open|<fallback-url>
 *   FOV_VOTE:row|<order>|<name>|<url>|<cooldownMins>|<logoUrl>   (repeated; logoUrl optional)
 *   FOV_VOTE:end
 *
 * cooldownMins is tri-state: -1 = a confirmed vote's reward is waiting, so the card shows a
 * gold Claim button (clicking sends "::claimvote panel" — the server claims and re-streams
 * the batch so the window refreshes); 0 = ready, green Vote button; >0 = quiet countdown.
 * Clicking Vote also flips that card to Claim locally, so once the toplist confirms the
 * reward is one click away without reopening the window.
 *
 * logoUrl points at the toplist's own vote-badge image; LofVoteLogoCache fetches and
 * caches it off-thread and the window draws a lettered tile until it's available.
 *
 * Back-compat both ways:
 *  - OLD clients (the first lofvote, which knew only "open|<url>" = browse) receive the
 *    same batch: they open the website's vote page off the open| line and harmlessly
 *    rewrite the rest — voting still works, just in the browser.
 *  - This client with an OLD server (plain "open|<url>" and nothing else) shows a
 *    one-row window pointing at the website's vote page.
 *
 * Only https:// URLs are ever opened, and the batch is server-authored (CONSOLE cannot
 * be produced by player chat), so a spoofed line can't hijack the browser.
 *
 * Local test without the server: type ::votepanel to open a sample window.
 */
package net.runelite.client.plugins.lofvote;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Vote",
	description = "Shows ::vote as a clickable window of every toplist, with your username already attached.",
	tags = {"fov", "lof", "vote", "toplist"},
	enabledByDefault = true,
	hidden = true // not player-toggleable: disabling it would silently break ::vote
)
public class LofVotePlugin extends Plugin
{
	/** Machine prefix the server drives this plugin with (must match DailyVotePlugin). */
	static final String PREFIX = "FOV_VOTE:";

	/** The Claim button's command: claim pending vote rewards, then re-stream the batch so
	 *  the open window refreshes (old servers ignore the arg and just claim). */
	private static final String CLAIM_COMMAND = "::claimvote panel";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofVoteOverlay overlay;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	private LofVoteMouseListener mouseListener;
	private MouseWheelListener wheelListener;
	private LofVoteLogoCache logoCache;

	/** Committed sites (what the window draws) + the buffer filled while a batch streams in.
	 *  Both are only ever touched on the client thread, so no locking needed. */
	private final List<Site> sites = new ArrayList<>();
	private final List<Site> buffer = new ArrayList<>();
	private String bufferFallbackUrl = "";

	@Override
	protected void startUp()
	{
		logoCache = new LofVoteLogoCache(okHttpClient, executor);
		overlayManager.add(overlay);
		mouseListener = new LofVoteMouseListener(this, overlay);
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
		sites.clear();
		buffer.clear();
		logoCache = null;
	}

	/** Arrival-based capture: parse each line exactly once, the moment it arrives. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CONSOLE)
		{
			return;
		}
		final String message = event.getMessage();
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}
		handle(message.substring(PREFIX.length()));
	}

	/**
	 * Display-only hiding of the machine lines (this client renders CONSOLE in the chat box).
	 * Pure filter — blocks the line from the rebuilt display, never touches the buffers.
	 */
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
		intStack[intStackSize - 3] = 0; // block from display
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::votepanel opens a sample window locally (no server round-trip).
		if ("votepanel".equalsIgnoreCase(event.getCommand()))
		{
			handle("open|https://fallofvarrock.com/vote");
			handle("row|0|RSPS-List|https://fallofvarrock.com/vote|0|https://www.rsps-list.com/images/vote.jpg");
			handle("row|1|RuLocus|https://fallofvarrock.com/vote|-1|");
			handle("row|2|Moparscape|https://fallofvarrock.com/vote|187|");
			handle("row|3|Top100Arena|https://fallofvarrock.com/vote|0|");
			handle("row|4|TopG|https://fallofvarrock.com/vote|719|https://topg.org/topg.gif");
			handle("end");
		}
	}

	/** Parse one batch line (already stripped of the {@link #PREFIX}). */
	private void handle(String body)
	{
		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "open":
				bufferFallbackUrl = rest.trim();
				buffer.clear();
				break;
			case "row":
			{
				// order | name | url | cooldownMins | logoUrl(optional)
				final String[] f = rest.split("\\|", 5);
				if (f.length >= 3)
				{
					int cooldown = 0;
					if (f.length >= 4)
					{
						try
						{
							cooldown = Integer.parseInt(f[3].trim());
						}
						catch (NumberFormatException ignored)
						{
						}
					}
					final String logoUrl = f.length >= 5 ? f[4].trim() : "";
					buffer.add(new Site(f[1].trim(), f[2].trim(), cooldown, logoUrl));
				}
				break;
			}
			case "end":
				commit();
				// Hide the just-arrived machine lines immediately (this client doesn't run
				// chatFilterCheck on the add path). Rebuild-only; no buffer mutation.
				clientThread.invokeLater(client::refreshChat);
				break;
			default:
				log.debug("unknown vote trigger payload: '{}'", body);
				break;
		}
	}

	/** Open the window from the streamed batch. An old server sends bare "open|<url>" with no
	 *  rows or end — in that case the open| line alone must still work, so commit() also runs
	 *  when a row-less batch times out via the next open. Simplest robust rule: a batch with
	 *  rows opens the full window on end; a row-less end shows the one-row website fallback. */
	private void commit()
	{
		sites.clear();
		if (!buffer.isEmpty())
		{
			sites.addAll(buffer);
		}
		else if (!bufferFallbackUrl.isEmpty())
		{
			sites.add(new Site("fallofvarrock.com/vote", bufferFallbackUrl, 0, ""));
		}
		buffer.clear();
		if (!sites.isEmpty())
		{
			overlay.resetScroll();
			overlay.setVisible(true);
		}
	}

	/** Dismiss the window (X / click-away). */
	void close()
	{
		overlay.setVisible(false);
	}

	/** A click on a site card. A claimable card collects the pending reward (one
	 *  {@link #CLAIM_COMMAND} drains every pending vote reward); otherwise the site's vote
	 *  page opens in the default browser and the card's button flips to Claim, so the reward
	 *  is one click away once the toplist confirms. The window stays open either way so the
	 *  player can click through the rest of the toplists. */
	void clickSite(Site site)
	{
		if (site == null)
		{
			return;
		}
		if (site.claimable())
		{
			// One claim collects everything pending, so quiet every Claim button now; a new
			// server then re-streams the batch and redraws the authoritative state.
			for (Site s : sites)
			{
				s.claimPending = false;
			}
			log.info("claiming vote rewards from the vote window");
			clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, CLAIM_COMMAND, 0, 0, 0, -1));
			return;
		}
		if (!site.url.startsWith("https://"))
		{
			return;
		}
		log.info("opening vote page: {}", site.url);
		LinkBrowser.browse(site.url);
		site.claimPending = true;
	}

	List<Site> getSites()
	{
		return sites;
	}

	/** The toplist's badge image for {@code site}, or null while it loads / if it has none. */
	java.awt.image.BufferedImage logoFor(Site site)
	{
		final LofVoteLogoCache cache = logoCache;
		return cache == null || site.logoUrl.isEmpty() ? null : cache.get(site.logoUrl);
	}

	/** One toplist row: display name, ready-to-open vote URL, minutes until next vote
	 *  (0 = ready, -1 = confirmed vote with its reward unclaimed → Claim button), and the
	 *  site's badge-image URL ("" = none, draw the lettered tile). */
	static final class Site
	{
		final String name;
		final String url;
		final int cooldownMins;
		final String logoUrl;

		/** Set when the player opens this site's vote page from the window — an optimistic
		 *  local flip to the Claim button while the toplist's postback is still in flight.
		 *  Written on the mouse thread, read on the client thread, hence volatile. */
		volatile boolean claimPending;

		Site(String name, String url, int cooldownMins, String logoUrl)
		{
			this.name = name;
			this.url = url;
			this.cooldownMins = cooldownMins;
			this.logoUrl = logoUrl;
		}

		/** True when the card should offer Claim instead of Vote/countdown. */
		boolean claimable()
		{
			return cooldownMins < 0 || claimPending;
		}
	}
}

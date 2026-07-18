/*
 * Fall of Varrock — ::vote browser hand-off.
 *
 * Typing ::vote in-game makes the server send a one-shot "FOV_VOTE:open|<url>" CONSOLE
 * chat line (see the server-side DailyVotePlugin). This plugin catches it, opens the
 * URL — the website's vote page with the player's login pre-filled — in the player's
 * default browser, and rewrites the magic line in the chatbox to a friendly message.
 *
 * CONSOLE is used for the same reason as the lofcommands window: the line fires the
 * ChatMessage event exactly once and lives in its own buffer, so it can never evict
 * the player's game messages. This rev-228 client renders CONSOLE lines in the chat
 * box, so the line is rewritten in place (single line — no display filter needed).
 *
 * Only https://fallofvarrock.com URLs are opened, so a spoofed chat line can never
 * send the player's browser somewhere else.
 */
package net.runelite.client.plugins.lofvote;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Vote",
	description = "Opens the Fall of Varrock vote page in your browser when you type ::vote.",
	tags = {"fov", "lof", "vote", "toplist"},
	enabledByDefault = true,
	hidden = true // not player-toggleable: disabling it would silently break ::vote
)
public class LofVotePlugin extends Plugin
{
	/** Magic CONSOLE prefix the server drives this plugin with (must match DailyVotePlugin). */
	static final String PREFIX = "FOV_VOTE:";

	/** Only site URLs are ever opened — a spoofed line can't hijack the browser. */
	private static final String ALLOWED_URL_PREFIX = "https://fallofvarrock.com/";

	/** What the magic chat line is rewritten to in the chatbox. */
	private static final String FRIENDLY_TEXT = "Opening the vote page in your browser...";

	@Inject
	private Client client;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CONSOLE)
		{
			return;
		}

		final String msg = Text.removeTags(event.getMessage()).trim();
		if (!msg.startsWith(PREFIX))
		{
			return;
		}

		// Replace the magic string in the chatbox with a friendly line.
		if (event.getMessageNode() != null)
		{
			event.getMessageNode().setValue(FRIENDLY_TEXT);
			client.refreshChat();
		}

		final String payload = msg.substring(PREFIX.length()).trim();
		if (!payload.startsWith("open|"))
		{
			log.warn("unknown vote trigger payload: '{}'", payload);
			return;
		}

		final String url = payload.substring("open|".length()).trim();
		if (!url.startsWith(ALLOWED_URL_PREFIX))
		{
			log.warn("refusing to open non-site vote url: '{}'", url);
			return;
		}

		log.info("opening vote page: {}", url);
		LinkBrowser.browse(url);
	}
}

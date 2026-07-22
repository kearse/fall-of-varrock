/*
 * Fall of Varrock — first-login intro video.
 *
 * The server sends a one-shot "FOV_INTRO:play" BROADCAST chat line to brand-new
 * accounts on their first login (see the server-side IntroVideoPlugin). This plugin
 * catches that trigger, hides the magic string from the chatbox, and plays the intro
 * video docked over the game viewport (the client frame stays usable: move, minimize,
 * resize all work — but the game itself stays covered and the video can't be
 * dismissed). The overlay closes itself when the video ends.
 *
 * The video itself is NOT baked into the client. It is fetched from
 * https://fallofvarrock.com/client/intro.mp4 into ~/.fov-home/client/intro/ with
 * ETag validation, so swapping the intro is just replacing that one file on the VPS —
 * no client rebuild, no server deploy. See docs/intro-video.md.
 *
 * Playback is JavaFX Media (H.264 + AAC), shaded into the client jar; the FX runtime
 * self-extracts its Windows natives to ~/.openjfx/cache on first use. On platforms
 * without the shaded natives (mac/linux) toolkit init fails and the intro is skipped
 * gracefully — the game is never blocked by a playback failure.
 *
 * Local test without the server: type ::introtest in the chatbox.
 */
package net.runelite.client.plugins.lofintro;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Intro",
	description = "Plays the Fall of Varrock intro video on a new account's first login.",
	tags = {"fov", "intro", "video", "cinematic"},
	enabledByDefault = true,
	hidden = true // not player-toggleable: disabling it would make the intro skippable
)
public class LofIntroPlugin extends Plugin
{
	/** Magic BROADCAST prefix the server uses to drive this plugin (never shown to the player). */
	public static final String TRIGGER_PREFIX = "FOV_INTRO:";

	/** What the magic chat line is rewritten to in the chatbox. */
	private static final String WELCOME_TEXT = "Welcome to <col=dc2626>Fall of Varrock</col>!";

	/** How long to wait for the video download before giving up (trigger already received). */
	private static final long FETCH_WAIT_MS = 60_000L;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	private IntroVideoFetcher fetcher;
	private volatile boolean playedThisSession;

	@Override
	protected void startUp()
	{
		playedThisSession = false;
		fetcher = new IntroVideoFetcher(okHttpClient);
		// Prefetch in the background so the video is already local by the time a new
		// account finishes the login flow (~30 MB, typically done during the login screen).
		executor.execute(fetcher::prefetch);
	}

	@Override
	protected void shutDown()
	{
		fetcher = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Once-per-LOGIN guard, not once-per-client-process: without this, running
		// ::introtest (which also flips the guard via the server's echo) and then
		// logging into a brand-new account in the same client session would swallow
		// that account's one real first-login trigger.
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			playedThisSession = false;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.BROADCAST)
		{
			return;
		}

		final String msg = Text.removeTags(event.getMessage()).trim();
		if (!msg.startsWith(TRIGGER_PREFIX))
		{
			return;
		}

		// Replace the magic string in the chatbox with a friendly line.
		if (event.getMessageNode() != null)
		{
			event.getMessageNode().setValue(WELCOME_TEXT);
			client.refreshChat();
		}

		final String command = msg.substring(TRIGGER_PREFIX.length()).trim();
		log.info("intro trigger received: '{}' (alreadyPlayedThisLogin={})", command, playedThisSession);
		if ("play".equals(command) && !playedThisSession)
		{
			playedThisSession = true;
			startPlayback(true); // report back to the server when it ends (drives the first-login flow)
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::introtest replays the intro locally (no server round-trip, no once-guard, no report).
		if ("introtest".equalsIgnoreCase(event.getCommand()))
		{
			startPlayback(false);
		}
	}

	/**
	 * @param report when true, send "::lofintro done" to the server once the video finishes (or can't
	 *   play). The server-side first-login flow waits on that signal to open the character-style window,
	 *   so a failed download must report IMMEDIATELY — never leave a new player stranded on a black video.
	 */
	private void startPlayback(boolean report)
	{
		final IntroVideoFetcher f = fetcher;
		final Runnable onFinished = report ? () -> send("::lofintro done") : null;
		if (f == null)
		{
			if (onFinished != null)
			{
				onFinished.run();
			}
			return;
		}
		executor.execute(() ->
		{
			final File video = f.awaitVideo(FETCH_WAIT_MS);
			if (video == null)
			{
				log.warn("intro video unavailable (download failed and no cached copy) — skipping intro");
				if (onFinished != null)
				{
					onFinished.run();
				}
				return;
			}
			SwingUtilities.invokeLater(() -> IntroVideoWindow.play(client.getCanvas(), video, onFinished));
		});
	}

	/** Post a chat-script command the server routes (MessagePublicHandler); mirrors LofStylePlugin.send. */
	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

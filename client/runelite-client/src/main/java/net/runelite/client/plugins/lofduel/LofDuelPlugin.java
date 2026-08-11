/*
 * Fall of Varrock — Duel Arena rules + confirmation windows.
 *
 * When two players agree a duel the server (DuelRulesClientMenu) publishes the shared rules state
 * in varp 4630; this plugin's rules overlay renders the themed rules grid and forwards toggle/
 * accept/decline clicks as "::lofduel <action>" public chat, which the server intercepts
 * (MessagePublicHandler → duelclick). When both stakes are accepted the server raises the
 * CONFIRMATION screen state in varp 4685 (DuelConfirmScreen) and streams the opponent's combat
 * stats as one machine CONSOLE line ("~LOFDUEL~stats|name|combat|a,s,d,h,r,m,p") — consumed here
 * on arrival (ChatMessage fires once per line) and display-filtered from the chat box with a
 * block-only chatFilterCheck, per the §8 chat-transport rules. No cache interface (those crash
 * our client), no custom packets.
 */
package net.runelite.client.plugins.lofduel;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Duel Rules",
	description = "Themed Duel Arena rules + confirmation windows opened when a duel is agreed.",
	tags = {"lof", "duel", "arena", "stake", "rules", "confirm"},
	enabledByDefault = true
)
public class LofDuelPlugin extends Plugin
{
	/** Machine prefix of the opponent-stats CONSOLE line (must match server DuelConfirmScreen). */
	static final String STATS_PREFIX = "~LOFDUEL~";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofDuelOverlay overlay;

	@Inject
	private LofDuelConfirmOverlay confirmOverlay;

	private LofDuelMouseListener mouseListener;

	/** The opponent's identity + combat stats for the confirmation screen (null until streamed). */
	private volatile OpponentStats opponentStats;

	/** name | combat level | attack, strength, defence, hitpoints, ranged, magic, prayer. */
	static final class OpponentStats
	{
		final String name;
		final int combat;
		final int[] stats;

		OpponentStats(String name, int combat, int[] stats)
		{
			this.name = name;
			this.combat = combat;
			this.stats = stats;
		}
	}

	@Provides
	LofDuelConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofDuelConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(confirmOverlay);
		mouseListener = new LofDuelMouseListener(this, overlay, confirmOverlay);
		mouseManager.registerMouseListener(mouseListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(confirmOverlay);
		if (mouseListener != null)
		{
			mouseManager.unregisterMouseListener(mouseListener);
			mouseListener = null;
		}
		opponentStats = null;
	}

	OpponentStats opponentStats()
	{
		return opponentStats;
	}

	/**
	 * Consume the opponent-stats line the moment it ARRIVES (fires exactly once per line).
	 * Format: stats|name|combat|a,s,d,h,r,m,p
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CONSOLE)
		{
			return;
		}
		final String message = event.getMessage();
		if (message == null || !message.startsWith(STATS_PREFIX))
		{
			return;
		}
		final String[] f = message.substring(STATS_PREFIX.length()).split("\\|", 4);
		if (f.length >= 4 && "stats".equals(f[0]))
		{
			try
			{
				final String[] parts = f[3].split(",");
				final int[] stats = new int[parts.length];
				for (int i = 0; i < parts.length; i++)
				{
					stats[i] = Integer.parseInt(parts[i].trim());
				}
				opponentStats = new OpponentStats(f[1], Integer.parseInt(f[2]), stats);
			}
			catch (NumberFormatException ignored)
			{
				// A malformed line renders as "stats unavailable" rather than throwing.
			}
		}
		// Hide the just-arrived machine line immediately (this client doesn't run
		// chatFilterCheck on the add path). Rebuild-only; no buffer mutation.
		clientThread.invokeLater(client::refreshChat);
	}

	/**
	 * Display-only hiding of the machine line (this client renders CONSOLE in the chat box).
	 * Pure filter — blocks the line from the rebuilt display, never touches the buffers; parsing
	 * happens in onChatMessage only.
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
		if (message == null || !message.startsWith(STATS_PREFIX))
		{
			return;
		}
		final int[] intStack = client.getIntStack();
		final int intStackSize = client.getIntStackSize();
		intStack[intStackSize - 3] = 0; // block from display
	}

	/** Send a rules interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		sendToken("::lofduel " + action);
	}

	/** Send a stake interaction (the confirmation screen's Accept/Decline route through the
	 *  SECURE TradeSession, same as the stake window). */
	void sendStakeAction(String action)
	{
		sendToken("::lofstake " + action);
	}

	private void sendToken(String msg)
	{
		// lof-prefixed tokens dodge the gamepack's scam-bait block list — they never leave the client.
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

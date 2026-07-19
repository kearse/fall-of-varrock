/*
 * Fall of Varrock — Feudal Ranks window.
 *
 * The Duke (server) pulses varp 4618 with (title ordinal + 1); this plugin opens the CoD-style
 * progression window (current emblem, coin bar, next-rank showcase, emblem track). Clicking
 * RANK UP sends "::rank buy <ordinal>" as public chat, which the server intercepts
 * (MessagePublicHandler → rankclick), performs the purchase, and re-pulses the varp so the open
 * window advances in place. Rank table mirrors the server Title enum (LofRanksData).
 */
package net.runelite.client.plugins.lofranks;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Feudal Ranks",
	description = "Rank progression window opened by Duke Horacio.",
	tags = {"lof", "rank", "feudal", "title", "duke"},
	enabledByDefault = true
)
public class LofRanksPlugin extends Plugin
{
	/** Must match server RankMenu.OPEN_VARP; value = title ordinal + 1. */
	private static final int OPEN_VARP = 4618;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofRanksOverlay overlay;

	@Inject
	private LofRanksConfig config;

	private LofRanksMouseListener mouseListener;

	@Provides
	LofRanksConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofRanksConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofRanksMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
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
		overlay.setVisible(false);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != OPEN_VARP)
		{
			return;
		}
		final int value = client.getVarpValue(OPEN_VARP);
		if (value == 0 || !config.enabled())
		{
			return; // the pulse's falling edge never closes the window (see design system §8)
		}
		final int rank = Math.min(Math.max(value - 1, 0), LofRanksData.RANKS.length - 1);
		overlay.setCurrentRank(rank);
		overlay.setVisible(true);
	}

	/** Send the purchase to the server as a public-chat token it intercepts + suppresses. */
	void sendBuy(int rankOrdinal)
	{
		final String msg = "::rank buy " + rankOrdinal;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

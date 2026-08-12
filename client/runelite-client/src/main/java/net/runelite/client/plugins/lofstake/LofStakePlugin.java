/*
 * Fall of Varrock — Duel Arena stake window.
 *
 * A fully custom themed stake screen drawn over the server's trade interface (group 335) during a
 * staked duel. The overlay reads the live offers from that interface and forwards un-stake / accept
 * / decline as "::stake <action> [slot]" public chat, which the server intercepts
 * (MessagePublicHandler → stakeclick → the secure TradeSession). Adding items stays native.
 */
package net.runelite.client.plugins.lofstake;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Duel Stake",
	description = "Themed Duel Arena stake window over the trade screen.",
	tags = {"lof", "duel", "arena", "stake", "trade"},
	enabledByDefault = true
)
public class LofStakePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofStakeOverlay overlay;

	private LofStakeMouseListener mouseListener;

	@Provides
	LofStakeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofStakeConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofStakeMouseListener(this, overlay);
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
	}

	/**
	 * Suppress the native HOVER menu while the cursor is over the stake window: it rebuilds every
	 * client tick from what's UNDER the window, so without this the top-left hint reads
	 * "Walk here" / "Attack ..." for the world behind while hovering a staked item.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!overlay.isShowing() || client.isMenuOpen())
		{
			return;
		}
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m == null || overlay.hitTest(new java.awt.Point(m.getX(), m.getY())) == LofStakeOverlay.OUTSIDE)
		{
			return;
		}
		client.setMenuEntries(new MenuEntry[0]);
	}

	/** Send a stake interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		// "::stake" is on the gamepack's scam-bait block list — it never leaves the client.
		final String msg = "::lofstake " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

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
import net.runelite.api.ScriptID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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

	/** Send a stake interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		final String msg = "::stake " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

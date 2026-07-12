/*
 * Fall of Varrock — Duel Arena rules window.
 *
 * When two players agree a duel the server (DuelRulesClientMenu) publishes the shared rules state
 * in varp 4630; this plugin's overlay renders the themed rules grid and forwards toggle/accept/
 * decline clicks as "::duel <action>" public chat, which the server intercepts (MessagePublicHandler
 * → duelclick). No cache interface (those crash our client), no custom packets.
 */
package net.runelite.client.plugins.lofduel;

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
	name = "Lof Duel Rules",
	description = "Themed Duel Arena rules window opened when a duel is agreed.",
	tags = {"lof", "duel", "arena", "stake", "rules"},
	enabledByDefault = true
)
public class LofDuelPlugin extends Plugin
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
	private LofDuelOverlay overlay;

	private LofDuelMouseListener mouseListener;

	@Provides
	LofDuelConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofDuelConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofDuelMouseListener(this, overlay);
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

	/** Send a rules interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		final String msg = "::duel " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

/*
 * Fall of Varrock — Companion Sparring settings window.
 *
 * Challenging your own companion makes the server (SparringClientMenu) publish the bout settings
 * in varp 4680; this plugin's overlay renders the themed settings screen — the Duel Arena Options
 * layout with the paper-doll side replaced by the sparring partner's setup — and forwards clicks
 * as "::lofspar <action>" public chat, which the server intercepts (MessagePublicHandler →
 * sparclick). No cache interface (those crash our client), no custom packets.
 */
package net.runelite.client.plugins.lofspar;

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
	name = "Lof Companion Sparring",
	description = "Themed sparring settings window opened when you challenge your companion.",
	tags = {"lof", "companion", "spar", "pk", "training"},
	enabledByDefault = true
)
public class LofSparPlugin extends Plugin
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
	private LofSparOverlay overlay;

	private LofSparMouseListener mouseListener;

	@Provides
	LofSparConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofSparConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofSparMouseListener(this, overlay);
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

	/** Send a settings interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		final String msg = "::lofspar " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

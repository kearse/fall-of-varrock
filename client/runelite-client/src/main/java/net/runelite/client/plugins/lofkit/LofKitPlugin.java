/*
 * Fall of Varrock — Kit editor window.
 *
 * The server (content/kits/KitEditor) publishes the loadout state as varps (4640 control,
 * 4641..4679 slots); this plugin's overlay renders the LMS-style kit screen with REAL item
 * sprites and forwards clicks as "::kit <action>" public chat, which the server intercepts
 * (MessagePublicHandler → kitclick). No cache interface (those crash our client), no custom
 * packets — the lofduel/lofstake pattern.
 */
package net.runelite.client.plugins.lofkit;

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
	name = "Lof Kit Editor",
	description = "LMS-style kit loadout editor — build, save and load PK kits.",
	tags = {"lof", "kit", "loadout", "pk", "training"},
	enabledByDefault = true
)
public class LofKitPlugin extends Plugin
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
	private LofKitOverlay overlay;

	private LofKitMouseListener mouseListener;

	@Provides
	LofKitConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofKitConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofKitMouseListener(this, overlay);
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

	/** Send a kit interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		final String msg = "::kit " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

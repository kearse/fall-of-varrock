/*
 * Fall of Varrock — teleport portal menu.
 *
 * The home portal (server) pulses varp 4607 when used; this plugin opens a tabbed teleport window
 * (drawn client-side because cache interfaces don't render on our client). Clicking a row sends
 * "::tp <cat> <row>" as public chat, which the server intercepts (MessagePublicHandler) and turns
 * into the teleport. Category/row indices mirror the server registry (see LofTeleportsData).
 */
package net.runelite.client.plugins.lofteleports;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Teleports",
	description = "Tabbed teleport menu opened by the home portal.",
	tags = {"lof", "teleport", "portal", "menu", "travel"},
	enabledByDefault = true
)
public class LofTeleportsPlugin extends Plugin
{
	/** Must match server TeleportClientMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4607;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofTeleportsOverlay overlay;

	@Inject
	private LofTeleportsConfig config;

	private LofTeleportsMouseListener mouseListener;
	private MouseWheelListener wheelListener;

	@Provides
	LofTeleportsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofTeleportsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofTeleportsMouseListener(this, overlay);
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
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == OPEN_VARP && client.getVarpValue(OPEN_VARP) != 0 && config.enabled())
		{
			overlay.setActiveTab(0);
			overlay.setVisible(true);
		}
	}

	/** Send the chosen teleport to the server as a public-chat token it intercepts + suppresses. */
	void sendTeleport(int categoryIndex, int rowIndex)
	{
		final String msg = "::tp " + categoryIndex + " " + rowIndex;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

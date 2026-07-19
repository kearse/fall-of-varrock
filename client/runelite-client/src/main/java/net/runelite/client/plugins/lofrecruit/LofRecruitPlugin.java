/*
 * Fall of Varrock — Muster Companions window.
 *
 * General Zo (server) pulses varp 4619, packed:
 *   bit 0 open flag · bits 1-3 companions fielded · bits 4-6 rank cap · bits 7-10 title ordinal.
 * This plugin opens the recruiter window (three discipline cards + the banner strip). Clicking a
 * card sends "::zo recruit <melee|range|mage>" as public chat, which the server intercepts
 * (MessagePublicHandler → zoclick), runs the 1,000,000-coin muster, and re-pulses the varp so the
 * banner strip updates in place.
 */
package net.runelite.client.plugins.lofrecruit;

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
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Muster Companions",
	description = "Companion recruiter window opened by General Zo.",
	tags = {"lof", "companion", "recruit", "muster", "zo"},
	enabledByDefault = true
)
public class LofRecruitPlugin extends Plugin
{
	/** Must match server RecruitMenu.OPEN_VARP (packed — see class doc). */
	private static final int OPEN_VARP = 4619;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofRecruitOverlay overlay;

	@Inject
	private LofRecruitConfig config;

	private LofRecruitMouseListener mouseListener;

	@Provides
	LofRecruitConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofRecruitConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		mouseListener = new LofRecruitMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		LofWindows.unregister(overlay);
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
		LofWindows.onForeignSignal(event.getVarpId(), client.getVarpValue(event.getVarpId()));
		if (event.getVarpId() != OPEN_VARP)
		{
			return;
		}
		final int value = client.getVarpValue(OPEN_VARP);
		if (value == 0 || (value & 1) == 0 || !config.enabled())
		{
			return; // the pulse's falling edge never closes the window (design system §8)
		}
		overlay.setState((value >> 1) & 0x7, (value >> 4) & 0x7, (value >> 7) & 0xF);
		LofWindows.openExclusive(overlay);
		overlay.setVisible(true);
	}

	/** Send the chosen muster to the server as a public-chat token it intercepts + suppresses. */
	void sendRecruit(String style)
	{
		final String msg = "::lofzo recruit " + style;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	/** The Regalia tab: the server opens the native Commander's Regalia shop (real item sprites). */
	void sendRegalia()
	{
		final String msg = "::lofzo regalia";
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

/*
 * Fall of Varrock — Mire Dispenser window.
 *
 * The Mire Run agility course's loot dispenser (server: content/skills/agility MireDispenser)
 * pulses varp 4631, packed:
 *   bit 0 open flag · bit 1 always-set data marker · bit 2 attuned ·
 *   bits 3-6 banked laps · bits 7-13 lap streak.
 * This plugin opens the dispenser window (streak/multiplier/bank stat strip + the loot table +
 * the Attune/Claim action). Clicking a button sends "::mire <attune|claim>" as public chat, which
 * the server intercepts (MessagePublicHandler → mireclick), runs the transaction, and re-pulses
 * the varp so the open window updates in place. Lap completions also re-pulse (without the open
 * bit) so the streak and bank counters tick live while running the course.
 */
package net.runelite.client.plugins.lofmire;

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
	name = "Lof Mire Dispenser",
	description = "Mire Run agility course loot dispenser window.",
	tags = {"lof", "mire", "agility", "dispenser", "loot"},
	enabledByDefault = true
)
public class LofMirePlugin extends Plugin
{
	/** Must match server MireDispenser.OPEN_VARP (packed — see class doc). */
	private static final int OPEN_VARP = 4631;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofMireOverlay overlay;

	@Inject
	private LofMireConfig config;

	private LofMireMouseListener mouseListener;

	@Provides
	LofMireConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofMireConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofMireMouseListener(this, overlay);
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
		if (value == 0)
		{
			return; // the pulse's falling edge never closes the window (design system §8)
		}
		overlay.setState((value & 0x4) != 0, (value >> 3) & 0xF, (value >> 7) & 0x7F);
		if ((value & 1) != 0 && config.enabled())
		{
			overlay.setVisible(true);
		}
	}

	/** Send a dispenser action to the server as a public-chat token it intercepts + suppresses. */
	void sendMire(String action)
	{
		final String msg = "::lofmire " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

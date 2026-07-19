/*
 * Fall of Varrock — Bond Exchange window.
 *
 * The server pulses varp 4629 packed as: bit 0 open · bits 1-10 tradeable bonds · bits 11-20
 * claimed bonds. Actions send "::bond <claim|member|donor>" which the server intercepts
 * (MessagePublicHandler → bondclick) — the claim runs its native permanent-warning confirm
 * server-side, redemptions apply and the varp re-pulses so the wallet updates in place.
 */
package net.runelite.client.plugins.lofbonds;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Bond Exchange",
	description = "The Bond Merchant's exchange window.",
	tags = {"lof", "bond", "membership", "donor"},
	enabledByDefault = true
)
public class LofBondsPlugin extends Plugin
{
	/** Must match server BondMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4629;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofBondsOverlay overlay;

	private LofBondsMouseListener mouseListener;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		mouseListener = new LofBondsMouseListener(this, overlay);
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
		if (value == 0 || (value & 1) == 0)
		{
			return;
		}
		overlay.setWallet((value >> 1) & 0x3FF, (value >> 11) & 0x3FF);
		LofWindows.openExclusive(overlay);
		overlay.setVisible(true);
	}

	void sendAction(String action)
	{
		final String msg = "::lofbond " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

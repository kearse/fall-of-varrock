/*
 * Fall of Varrock — Character Style window (the on-brand makeover).
 *
 * Replaces the native character-design interface (its rev-228 layout didn't match the bound
 * component ids — misaligned, dead buttons). The classic layout, on-brand: a centred modal
 * with a see-through portrait hole in the middle — the player's own in-world model, framed by
 * the panel, is the live 3D preview. The server hides worn gear while the window is open (you
 * edit your default skins), and every arrow press sends "::lofstyle look/colour ..."
 * (MessagePublicHandler → styleclick) so the model updates instantly; closing without DONE
 * sends "::lofstyle close" so the gear comes back. Varp 4632 opens (bit 0), bit 1 = female.
 */
package net.runelite.client.plugins.lofstyle;

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
	name = "Lof Character Style",
	description = "Aurelia's restyling window — your in-world character is the live preview.",
	tags = {"lof", "style", "makeover", "appearance"},
	enabledByDefault = true
)
public class LofStylePlugin extends Plugin
{
	/** Must match server StyleMenu.OPEN_VARP; bit 0 open, bit 1 female. */
	private static final int OPEN_VARP = 4632;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofStyleOverlay overlay;

	private LofStyleMouseListener mouseListener;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		// any non-DONE dismissal must reach the server so it re-equips the player
		overlay.setCloseNotifier(this::sendClose);
		mouseListener = new LofStyleMouseListener(this, overlay);
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
		overlay.hideWindow(); // notifies close if it was open, so the gear comes back
		overlay.setCloseNotifier(null);
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
			return; // pulse falling edge never closes the window
		}
		overlay.setFemale((value & 2) != 0);
		LofWindows.openExclusive(overlay);
		overlay.setVisible(true);
	}

	void sendStep(boolean colour, int id, int delta)
	{
		send("::lofstyle " + (colour ? "colour " : "look ") + id + " " + delta);
	}

	void sendGender(boolean female)
	{
		send("::lofstyle gender " + (female ? "female" : "male"));
	}

	void sendDone()
	{
		send("::lofstyle done");
	}

	void sendClose()
	{
		send("::lofstyle close");
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

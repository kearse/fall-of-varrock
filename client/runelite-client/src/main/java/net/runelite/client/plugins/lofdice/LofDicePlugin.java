/*
 * Fall of Varrock — the Gambler's Table (house percentile dice).
 *
 * The server pulses varp 4628: value 1 opens the table; a result pulse carries
 * bit9 flag | roll in bits 1-7 | win in bit 8. Betting sends "::dice roll <amount>" which the
 * server intercepts (MessagePublicHandler → diceclick), validates, rolls and pays.
 * Odds mirror the server: 51+ wins, pays 2x minus the 5% house cut, 100M cap.
 */
package net.runelite.client.plugins.lofdice;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Gambler's Table",
	description = "The house dice table window.",
	tags = {"lof", "dice", "gamble", "bet"},
	enabledByDefault = true
)
public class LofDicePlugin extends Plugin
{
	/** Must match server DiceMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4628;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofDiceOverlay overlay;

	private LofDiceMouseListener mouseListener;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofDiceMouseListener(this, overlay);
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
			return;
		}
		if ((value & (1 << 9)) != 0)
		{
			// result pulse — burn the roll into the (already open) table
			overlay.onResult((value >> 1) & 0x7F, (value & (1 << 8)) != 0);
		}
		overlay.setVisible(true);
	}

	void sendRoll(long amount)
	{
		final String msg = "::dice roll " + amount;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

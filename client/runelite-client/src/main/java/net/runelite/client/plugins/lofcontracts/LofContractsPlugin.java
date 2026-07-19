/*
 * Fall of Varrock — War Contracts window (Vannaka's board).
 *
 * The server pushes the board state over a hidden ~LOFCON~ chat line
 * (streak|warEffort|combatName|left|total|resourceName|resLeft|resSkill, "-" for none) and
 * pulses varp 4626. Buttons send "::con <combat|resource|rewards>" which the server intercepts
 * (MessagePublicHandler → conclick) — assignment dialogue still plays in the chatbox (it's
 * narrative), and the board re-pushes so the window updates.
 */
package net.runelite.client.plugins.lofcontracts;

import javax.inject.Inject;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof War Contracts",
	description = "Vannaka's contract board window.",
	tags = {"lof", "slayer", "contract", "war effort", "vannaka"},
	enabledByDefault = true
)
public class LofContractsPlugin extends Plugin
{
	/** Must match server ContractMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4626;
	private static final String PREFIX = "~LOFCON~";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofContractsOverlay overlay;

	private LofContractsMouseListener mouseListener;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		mouseListener = new LofContractsMouseListener(this, overlay);
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
		if (event.getVarpId() == OPEN_VARP && client.getVarpValue(OPEN_VARP) != 0)
		{
			LofWindows.openExclusive(overlay);
			overlay.setVisible(true);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final String msg = event.getMessage();
		if (msg == null || !msg.startsWith(PREFIX))
		{
			return;
		}
		try
		{
			final String[] p = msg.substring(PREFIX.length()).split("\\|", -1);
			overlay.setState(
				Integer.parseInt(p[0]), Long.parseLong(p[1]),
				"-".equals(p[2]) ? null : p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
				"-".equals(p[5]) ? null : p[5], Integer.parseInt(p[6]), p[7]);
		}
		catch (Exception e)
		{
			// malformed line — render the last good state
		}
		final MessageNode node = event.getMessageNode();
		final ChatLineBuffer buffer = client.getChatLineMap().get(event.getType().getType());
		if (buffer != null && node != null)
		{
			buffer.removeMessageNode(node);
		}
	}

	void sendAction(String action)
	{
		final String msg = "::lofcon " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

/*
 * Fall of Varrock — War Forge window (the Royal Smith's recipes).
 *
 * The server pushes recipes + carried counts over hidden ~LOFFORGE~ chat lines
 * (header H|<n>|commId|emberId|barId|coinId|commHave|embersHave|barsHave|coinsHave, then
 * R|<i>|<style>|baseId|outId|comm|bars|coins|embers|baseHave per recipe) and pulses varp 4627.
 * Forging sends "::forge make <i>" which the server intercepts (MessagePublicHandler →
 * forgeclick), re-validates, forges, broadcasts, and re-pushes.
 */
package net.runelite.client.plugins.lofforge;

import java.util.ArrayList;
import java.util.List;
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
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof War Forge",
	description = "The Royal Smith's war-forging window.",
	tags = {"lof", "forge", "torva", "masori", "ancestral"},
	enabledByDefault = true
)
public class LofForgePlugin extends Plugin
{
	/** Must match server ForgeMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4627;
	private static final String PREFIX = "~LOFFORGE~";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofForgeOverlay overlay;

	private LofForgeMouseListener mouseListener;
	private MouseWheelListener wheelListener;

	private final List<LofForgeOverlay.Recipe> pendingRows = new ArrayList<>();
	private int pendingCount;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		mouseListener = new LofForgeMouseListener(this, overlay);
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
		LofWindows.unregister(overlay);
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
			parse(msg.substring(PREFIX.length()));
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

	private void parse(String body)
	{
		final String[] p = body.split("\\|", -1);
		if ("H".equals(p[0]) && p.length >= 10)
		{
			pendingCount = Integer.parseInt(p[1]);
			pendingRows.clear();
			overlay.setCurrencies(
				Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
				Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]));
			return;
		}
		if (!"R".equals(p[0]) || p.length < 10)
		{
			return;
		}
		final LofForgeOverlay.Recipe r = new LofForgeOverlay.Recipe();
		r.index = Integer.parseInt(p[1]);
		r.style = p[2];
		r.baseId = Integer.parseInt(p[3]);
		r.outId = Integer.parseInt(p[4]);
		r.comm = Integer.parseInt(p[5]);
		r.bars = Integer.parseInt(p[6]);
		r.coins = Integer.parseInt(p[7]);
		r.embers = Integer.parseInt(p[8]);
		r.baseHave = Integer.parseInt(p[9]);
		pendingRows.add(r);
		if (pendingRows.size() >= pendingCount)
		{
			overlay.setRecipes(new ArrayList<>(pendingRows));
		}
	}

	void sendForge(int index)
	{
		final String msg = "::lofforge make " + index;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

/*
 * Fall of Varrock — the making window (reusable production UI).
 *
 * The server pushes a recipe list over hidden ~LOFMAKE~ chat lines (header H|<kind>|<title>|<n>,
 * then R|<i>|resultId|level|xp10|matId:qty;... per recipe) and pulses varp 4625 (value = kind:
 * 1 furnace, 2 anvil — the same frame serves any production skill). Rows draw real item icons;
 * clicking MAKE sends "::make <resultId> <qty>" which the server intercepts (MessagePublicHandler
 * → makeclick) and runs the production loop.
 */
package net.runelite.client.plugins.lofmake;

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
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Making Window",
	description = "Production window for the furnace, anvil and other making stations.",
	tags = {"lof", "smith", "smelt", "make", "craft"},
	enabledByDefault = true
)
public class LofMakePlugin extends Plugin
{
	/** Must match server SmithingPlugin.OPEN_VARP; value = kind (1 furnace, 2 anvil). */
	private static final int OPEN_VARP = 4625;
	private static final String PREFIX = "~LOFMAKE~";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofMakeOverlay overlay;

	private LofMakeMouseListener mouseListener;

	/** Accumulator for an incoming recipe list (header resets it; rows fill it). */
	private String pendingTitle = "";
	private int pendingCount;
	private final List<LofMakeOverlay.Recipe> pendingRows = new ArrayList<>();

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		LofWindows.register(overlay);
		mouseListener = new LofMakeMouseListener(this, overlay);
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
		if (value == 0)
		{
			return; // pulse falling edge never closes the window
		}
		LofWindows.openExclusive(overlay);
		overlay.setVisible(true);
	}

	/**
	 * The recipe feed. Matched by content prefix on ANY chat type (the rev-228 client can surface
	 * server CONSOLE under a different type — see CompanionsPlugin), parsed, then removed from the
	 * chat buffer so it stays invisible.
	 */
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
			// a malformed line renders nothing rather than throwing (design system §1)
		}
		suppress(event);
	}

	private void parse(String body)
	{
		final String[] p = body.split("\\|", -1);
		if ("H".equals(p[0]) && p.length >= 4)
		{
			pendingTitle = p[2];
			pendingCount = Integer.parseInt(p[3]);
			pendingRows.clear();
			if (pendingCount == 0)
			{
				overlay.setRecipes(pendingTitle, new ArrayList<>());
			}
			return;
		}
		if (!"R".equals(p[0]) || p.length < 6)
		{
			return;
		}
		final LofMakeOverlay.Recipe r = new LofMakeOverlay.Recipe();
		r.resultId = Integer.parseInt(p[2]);
		r.level = Integer.parseInt(p[3]);
		r.xp10 = Integer.parseInt(p[4]);
		for (String mat : p[5].split(";"))
		{
			if (mat.isEmpty())
			{
				continue;
			}
			final String[] iq = mat.split(":");
			r.mats.add(new int[]{Integer.parseInt(iq[0]), Integer.parseInt(iq[1])});
		}
		pendingRows.add(r);
		if (pendingRows.size() >= pendingCount)
		{
			overlay.setRecipes(pendingTitle, new ArrayList<>(pendingRows));
		}
	}

	private void suppress(ChatMessage event)
	{
		final MessageNode node = event.getMessageNode();
		final ChatLineBuffer buffer = client.getChatLineMap().get(event.getType().getType());
		if (buffer != null && node != null)
		{
			buffer.removeMessageNode(node);
		}
	}

	/** Send the make order as a public-chat token the server intercepts + suppresses. */
	void sendMake(int resultId, int qty)
	{
		final String msg = "::lofmake " + resultId + " " + qty;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

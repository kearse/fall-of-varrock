/*
 * Fall of Varrock — Supply Depot window (the Quartermaster as a sell-to-the-war store).
 *
 * The server pushes the accepted-goods manifest over hidden ~LOFSUP~ chat lines
 * (header H|<nCats>|<driveIdx>|<mult>|<meter>|<max>|<warEffort>, then chunked
 * C|<catIdx>|<label>|<chunk>|<last>|itemId:weEach:carried;... lines) and pulses varp 4624.
 * Selling sends "::sup cat <i>" / "::sup item <id> <qty|all>" / "::sup all", which the server
 * intercepts (MessagePublicHandler → supclick), deposits, and re-pushes so the window updates.
 */
package net.runelite.client.plugins.lofsupply;

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
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Supply Depot",
	description = "The Quartermaster's war-supply store window.",
	tags = {"lof", "supply", "quartermaster", "war effort"},
	enabledByDefault = true
)
public class LofSupplyPlugin extends Plugin
{
	/** Must match server SupplyMenu.OPEN_VARP. */
	private static final int OPEN_VARP = 4624;
	private static final String PREFIX = "~LOFSUP~";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofSupplyOverlay overlay;

	private LofSupplyMouseListener mouseListener;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofSupplyMouseListener(this, overlay);
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
		if (client.getVarpValue(OPEN_VARP) != 0)
		{
			overlay.setVisible(true);
		}
	}

	/** The manifest feed — prefix-matched on any chat type, parsed, then hidden (CompanionsPlugin pattern). */
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
			// a malformed line renders nothing rather than throwing
		}
		suppress(event);
	}

	private void parse(String body)
	{
		final String[] p = body.split("\\|", -1);
		if ("H".equals(p[0]) && p.length >= 7)
		{
			overlay.beginManifest(
				Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
				Integer.parseInt(p[4]), Integer.parseInt(p[5]), Long.parseLong(p[6]));
			return;
		}
		if (!"C".equals(p[0]) || p.length < 6)
		{
			return;
		}
		final int catIdx = Integer.parseInt(p[1]);
		final String label = p[2];
		final int chunk = Integer.parseInt(p[3]);
		final List<LofSupplyOverlay.Entry> entries = new ArrayList<>();
		for (String item : p[5].split(";"))
		{
			if (item.isEmpty())
			{
				continue;
			}
			final String[] f = item.split(":");
			final LofSupplyOverlay.Entry e = new LofSupplyOverlay.Entry();
			e.id = Integer.parseInt(f[0]);
			e.we = Integer.parseInt(f[1]);
			e.carried = Integer.parseInt(f[2]);
			entries.add(e);
		}
		overlay.addCategoryChunk(catIdx, label, chunk == 0, entries);
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

	void sendCategory(int catIdx)
	{
		send("::sup cat " + catIdx);
	}

	void sendItem(int itemId, int qty)
	{
		send("::sup item " + itemId + " " + (qty <= 0 ? "all" : String.valueOf(qty)));
	}

	void sendAll()
	{
		send("::sup all");
	}

	private void send(String msg)
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}
}

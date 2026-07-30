/*
 * Fall of Varrock — Companions management panel.
 *
 * The server pushes each companion's full state (levels, HP, orders, attack style, autocast, toggles,
 * ammo, and worn gear) over a hidden CONSOLE chat line tagged ~LOFCMP~, and replies to gear-picker
 * requests over ~LOFGEAR~. This plugin parses those, feeds the side panel, sends the matching
 * ::companion commands, and tidies companions out of the right-click "Attack" menu. Auto-discovered;
 * on by default.
 */
package net.runelite.client.plugins.companions;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Companions",
	description = "Manage your companions: levels, attack style, autocast, gear from bank, ammo, orders.",
	tags = {"lof", "companion", "knight", "bot", "panel"},
	enabledByDefault = true
)
public class CompanionsPlugin extends Plugin
{
	private static final int MAX = 3;
	private static final int EQUIP_SLOTS = 14; // OSRS worn-equipment container capacity (slots 0..13)
	/** Tags for the server's companion channels (must match CompanionRegistry). */
	private static final String STATE_PREFIX = "~LOFCMP~";
	private static final String GEAR_PREFIX = "~LOFGEAR~";

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	private CompanionsPanel panel;
	private NavigationButton navButton;
	private final int[] companionIndices = {-1, -1, -1};
	/** Display names per slot (null = no companion there) — drives the bank "Equip on …" menu. */
	private final String[] companionNames = new String[MAX];
	/** Accumulators for the per-companion ~LOFCMP~ feed (rebuilt once a whole roster snapshot arrives). */
	private final java.util.Map<Integer, CompanionsPanel.CompanionRow> accRows = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> accIdx = new java.util.HashMap<>();
	/** Accumulator for the chunked ~LOFGEAR~ picker reply. */
	private final List<CompanionsPanel.GearOption> accGear = new ArrayList<>();

	private final CompanionsPanel.Actions actions = new CompanionsPanel.Actions()
	{
		public void order(String arg) { send("::lofcompanion " + arg); }
		public void equip(int slot, int itemId) { send("::lofcompanion equip " + (slot + 1) + " " + itemId); }
		public void unequip(int slot, int equipSlot) { send("::lofcompanion unequip " + (slot + 1) + " " + equipSlot); }
		public void style(int slot, int style) { send("::lofcompanion style " + (slot + 1) + " " + style); }
		public void spell(int slot, String spellName) { send("::lofcompanion spell " + (slot + 1) + " " + spellName); }
		public void retaliate(int slot) { send("::lofcompanion retaliate " + (slot + 1)); }
		public void loot(int slot) { send("::lofcompanion loot " + (slot + 1)); }
		public void rename(int slot, String name) { send("::lofcompanion rename " + (slot + 1) + " " + name); }
		public void requestGear(int slot, int equipSlot) { send("::lofcompanion gear " + (slot + 1) + " " + equipSlot); }
		public void dismiss(int slot) { send("::lofcompanion dismiss " + (slot + 1)); }
		public void summon(int slot) { send("::lofcompanion summon " + (slot + 1)); }
		public void dismissAll() { send("::lofcompanion dismiss"); }
		public void summonAll() { send("::lofcompanion summon"); }
	};

	@Override
	protected void startUp()
	{
		panel = new CompanionsPanel(actions, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Companions")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		Arrays.fill(companionIndices, -1);
		Arrays.fill(companionNames, null);
	}

	/** Send a server command via the chat-send clientscript (handles long strings; bypasses chat typing limits). */
	private void send(String command)
	{
		// 5517 takes 1 string + 4 ints (ScriptID.CHAT_SEND / the cache script's trailer). Passing a
		// string for the clan-target arg mismatched the stacks and the send never happened.
		clientThread.invoke(() -> client.runScript(ScriptID.CHAT_SEND, command, 0, 0, 0, -1));
	}

	/**
	 * Right-clicking a bank item adds an "Equip on Sir &lt;Name&gt;" option per live companion (the
	 * server pulls it from the bank, fits the slot, and returns any displaced gear). The panel's
	 * per-slot picker is the primary path; this stays as a convenient shortcut.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		final MenuEntry[] entries = event.getMenuEntries();
		int itemId = -1;
		for (final MenuEntry e : entries)
		{
			if (e.getParam1() == ComponentID.BANK_ITEM_CONTAINER && e.getItemId() > 0)
			{
				itemId = e.getItemId();
				break;
			}
		}
		if (itemId < 0)
		{
			return;
		}
		final int fItemId = itemId;
		for (int slot = 0; slot < MAX; slot++)
		{
			final String nm = companionNames[slot];
			if (nm == null)
			{
				continue;
			}
			final int fSlot = slot;
			client.createMenuEntry(1)
				.setOption("Equip on")
				.setTarget("<col=ffae00>Sir " + nm + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(me -> actions.equip(fSlot, fItemId));
		}
	}

	/**
	 * Wipe the panel and all cached state whenever the local player leaves the world (logout to the
	 * login screen, world hop, or a dropped connection). The panel is driven purely by the server's
	 * ~LOFCMP~ feed, which is only sent to accounts that field companions — so without this a companion
	 * roster from a PREVIOUS account (an account switch on the same client) would linger on-screen until
	 * something happened to send a fresh snapshot. Clearing on the state change guarantees a clean slate.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState s = event.getGameState();
		if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING || s == GameState.CONNECTION_LOST)
		{
			accRows.clear();
			accIdx.clear();
			accGear.clear();
			Arrays.fill(companionIndices, -1);
			Arrays.fill(companionNames, null);
			if (panel != null)
			{
				panel.update(new ArrayList<>());
			}
		}
	}

	/**
	 * Both companion channels arrive as hidden server-sent chat lines. We match by the {@code ~LOF}
	 * prefix on ANY {@link ChatMessageType} (not just CONSOLE): our server is rev-mismatched from this
	 * stock rev-228 client, so the server's CONSOLE opcode can surface under a different chat type on
	 * the client — a strict {@code == CONSOLE} filter silently drops the feed. Matching by content is
	 * type-agnostic and robust.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final String msg = event.getMessage();
		if (msg == null || (!msg.startsWith(STATE_PREFIX) && !msg.startsWith(GEAR_PREFIX)))
		{
			return;
		}
		try
		{
			if (msg.startsWith(STATE_PREFIX))
			{
				parseState(msg);
			}
			else
			{
				parseGearList(msg);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse companion message: {}", msg, e);
		}
		suppress(event);
	}

	/**
	 * Decode one per-companion ~LOFCMP~ message: {@code i/n|META|SKILLS|EQUIP|STYLES}. The server sends
	 * one line per companion (long single-line rosters get dropped by the client), so we accumulate
	 * i=0..n-1 into {@link #accRows} and rebuild the panel once the whole roster has arrived. {@code 0/0}
	 * clears the panel.
	 */
	private void parseState(String msg)
	{
		final String body = msg.substring(STATE_PREFIX.length());
		final int firstBar = body.indexOf('|');
		final String[] hn = (firstBar >= 0 ? body.substring(0, firstBar) : body).split("/");
		final int n = hn.length > 1 ? parseInt(hn[1]) : 0;
		if (n <= 0) // empty roster
		{
			accRows.clear();
			accIdx.clear();
			Arrays.fill(companionIndices, -1);
			Arrays.fill(companionNames, null);
			if (panel != null)
			{
				panel.update(new ArrayList<>());
			}
			return;
		}
		final int i = parseInt(hn[0]);
		if (i == 0)
		{
			accRows.clear(); // start of a fresh roster snapshot
			accIdx.clear();
		}
		final String[] sec = (firstBar >= 0 ? body.substring(firstBar + 1) : "").split("\\|", -1); // META|SKILLS|EQUIP|STYLES
		final String[] m = sec[0].split(",");
		if (m.length < 13)
		{
			return;
		}
		final int idx = Integer.parseInt(m[0]);
		final String name = m[1];
		final int arch = Integer.parseInt(m[2]);
		final int combat = Integer.parseInt(m[3]);
		final int curHp = Integer.parseInt(m[4]);
		final int maxHp = Integer.parseInt(m[5]);
		final int orders = Integer.parseInt(m[6]);
		final int styleVarp = Integer.parseInt(m[7]);
		final String autocast = m[8];
		final boolean autoLoot = "1".equals(m[9]);
		final boolean retaliate = "1".equals(m[10]);
		final int ammoId = Integer.parseInt(m[11]);
		final int ammoQty = Integer.parseInt(m[12]);
		// state (0 fielded / 1 dismissed / 2 slain) was appended after the original 13 fields — treat a
		// short line as "fielded" so an older server still renders.
		final int state = m.length > 13 ? parseInt(m[13]) : CompanionsPanel.STATE_FIELDED;

		final int[] levels = new int[7];
		if (sec.length > 1)
		{
			final String[] sk = sec[1].split(",");
			for (int k = 0; k < levels.length && k < sk.length; k++)
			{
				levels[k] = parseInt(sk[k]);
			}
		}

		final int[] equipment = new int[EQUIP_SLOTS];
		final int[] equipQty = new int[EQUIP_SLOTS];
		Arrays.fill(equipment, -1);
		if (sec.length > 2 && !sec[2].isEmpty())
		{
			for (final String e : sec[2].split(";"))
			{
				if (e.isEmpty())
				{
					continue;
				}
				final String[] p = e.split(":");
				if (p.length >= 3)
				{
					final int es = parseInt(p[0]);
					if (es >= 0 && es < EQUIP_SLOTS)
					{
						equipment[es] = parseInt(p[1]);
						equipQty[es] = parseInt(p[2]);
					}
				}
			}
		}

		final String[] styleLabels = (sec.length > 3 && !sec[3].isEmpty())
			? sec[3].split("/")
			: new String[0];

		// Resolve item names HERE — onChatMessage runs on the client thread, where getItemComposition
		// is legal. The Swing panel (EDT) must never call it (throws "must be called on client thread").
		final String ammoName = ammoId > 0 ? safeName(ammoId) : null;
		accRows.put(i, new CompanionsPanel.CompanionRow(i, name, arch, combat, curHp, maxHp, orders,
			styleVarp, autocast, autoLoot, retaliate, ammoId, ammoQty, ammoName, levels, equipment, equipQty,
			styleLabels, state));
		accIdx.put(i, idx);

		if (accRows.size() >= n) // whole roster received → rebuild
		{
			final List<CompanionsPanel.CompanionRow> rows = new ArrayList<>();
			Arrays.fill(companionIndices, -1);
			Arrays.fill(companionNames, null);
			for (int s = 0; s < n && s < MAX; s++)
			{
				final CompanionsPanel.CompanionRow r = accRows.get(s);
				if (r == null)
				{
					continue;
				}
				rows.add(r);
				// Only a FIELDED companion gets an index/name here: the index drives the Attack-menu hide
				// (a benched one isn't rendered) and the name drives the bank "Equip on …" shortcut, which
				// the server can only honour for someone actually in the world.
				companionIndices[s] = r.fielded() ? accIdx.getOrDefault(s, -1) : -1;
				companionNames[s] = r.fielded() ? r.name : null;
			}
			if (panel != null)
			{
				panel.update(rows);
			}
		}
	}

	/**
	 * Decode a chunked ~LOFGEAR~ reply: {@code slot,equipSlot,chunkIdx,last~itemId:qty:wearable;...}.
	 * Bank lists can be long, so the server chunks them; we reset on chunk 0 and render on last=1.
	 */
	private void parseGearList(String msg)
	{
		final String body = msg.substring(GEAR_PREFIX.length());
		final int tilde = body.indexOf('~');
		if (tilde < 0)
		{
			return;
		}
		final String[] hdr = body.substring(0, tilde).split(",");
		if (hdr.length < 4)
		{
			return;
		}
		final int slot = parseInt(hdr[0]);
		final int equipSlot = parseInt(hdr[1]);
		final int chunkIdx = parseInt(hdr[2]);
		final int last = parseInt(hdr[3]);
		if (chunkIdx == 0)
		{
			accGear.clear();
		}
		final String list = body.substring(tilde + 1);
		if (!list.isEmpty())
		{
			for (final String e : list.split(";"))
			{
				if (e.isEmpty())
				{
					continue;
				}
				final String[] p = e.split(":");
				if (p.length >= 3)
				{
					final int id = parseInt(p[0]);
					accGear.add(new CompanionsPanel.GearOption(id, parseInt(p[1]), "1".equals(p[2]), safeName(id)));
				}
			}
		}
		if (last == 1 && panel != null)
		{
			panel.showGearOptions(slot, equipSlot, new ArrayList<>(accGear));
			accGear.clear();
		}
	}

	private static int parseInt(String s)
	{
		try
		{
			return Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** Resolve an item's name. MUST be called on the client thread (onChatMessage is) — getItemComposition throws on the EDT. */
	private String safeName(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		try
		{
			final String n = itemManager.getItemComposition(itemId).getName();
			return n != null ? n : ("Item " + itemId);
		}
		catch (Exception e)
		{
			return "Item " + itemId;
		}
	}

	/** Delete a companion-channel line from the chatbox so it stays invisible. */
	private void suppress(ChatMessage event)
	{
		final MessageNode node = event.getMessageNode();
		final ChatLineBuffer buffer = client.getChatLineMap().get(event.getType().getType());
		if (buffer != null && node != null)
		{
			buffer.removeMessageNode(node);
		}
	}

	/** Hide the "Attack" option on your own companions (matched by rendered world-index). */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!"Attack".equals(event.getOption()) || !isCompanion(event.getIdentifier()))
		{
			return;
		}
		final MenuEntry[] entries = client.getMenuEntries();
		client.setMenuEntries(Arrays.stream(entries)
			.filter(e -> !("Attack".equals(e.getOption()) && isCompanion(e.getIdentifier())))
			.toArray(MenuEntry[]::new));
	}

	private boolean isCompanion(int worldIndex)
	{
		for (int idx : companionIndices)
		{
			if (idx >= 0 && idx == worldIndex)
			{
				return true;
			}
		}
		return false;
	}

	private static BufferedImage icon()
	{
		final BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		final Graphics g = img.getGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, 24, 24);
		g.setColor(new Color(220, 160, 30));
		g.setFont(g.getFont().deriveFont(16f));
		g.drawString("L", 8, 18);
		g.dispose();
		return img;
	}
}

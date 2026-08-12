/*
 * Fall of Varrock — Kit editor window.
 *
 * The server (content/kits/KitEditor) publishes the loadout state as varps (4640 control,
 * 4641..4679 slots); this plugin's overlay renders the LMS-style kit screen with REAL item
 * sprites and forwards clicks as "::kit <action>" public chat, which the server intercepts
 * (MessagePublicHandler → kitclick). No cache interface (those crash our client), no custom
 * packets — the lofduel/lofstake pattern.
 */
package net.runelite.client.plugins.lofkit;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Kit Editor",
	description = "LMS-style kit loadout editor — build, save and load PK kits.",
	tags = {"lof", "kit", "loadout", "pk", "training"},
	enabledByDefault = true
)
public class LofKitPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private LofKitOverlay overlay;

	private LofKitMouseListener mouseListener;
	private MouseWheelListener wheelListener;
	private KeyListener searchKeyListener;

	@Provides
	LofKitConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofKitConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofKitMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
		// Wheel scrolls the bank browser grid (the LofTeleports pattern).
		wheelListener = event ->
		{
			if (overlay.handleScroll(event.getPoint(), event.getWheelRotation()))
			{
				event.consume();
			}
			return event;
		};
		mouseManager.registerMouseWheelListener(wheelListener);
		// The live search field (bank AND training — it filters your bank / the armoury pool):
		// while it has focus every keystroke edits the query and is CONSUMED, so typing never
		// leaks into the chatbox or game hotkeys. Esc also drops a held item (the hand cursor)
		// even when the field isn't focused.
		searchKeyListener = new KeyListener()
		{
			private boolean active()
			{
				return overlay.isShowing() && !overlay.isLms() && overlay.isSearchFocused();
			}

			@Override
			public void keyTyped(KeyEvent e)
			{
				if (!active())
				{
					return;
				}
				final char c = e.getKeyChar();
				if (c == '\b')
				{
					overlay.searchBackspace();
				}
				else if (c >= ' ' && c != KeyEvent.CHAR_UNDEFINED)
				{
					overlay.searchAppend(c);
				}
				e.consume();
			}

			@Override
			public void keyPressed(KeyEvent e)
			{
				// Esc drops the held item regardless of search focus.
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE && overlay.isShowing() && overlay.hasHand())
				{
					overlay.clearHand();
					e.consume();
					return;
				}
				if (!active())
				{
					return;
				}
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE || e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					overlay.setSearchFocused(false);
				}
				e.consume();
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
				if (active())
				{
					e.consume();
				}
			}
		};
		keyManager.registerKeyListener(searchKeyListener);
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
		if (wheelListener != null)
		{
			mouseManager.unregisterMouseWheelListener(wheelListener);
			wheelListener = null;
		}
		if (searchKeyListener != null)
		{
			keyManager.unregisterKeyListener(searchKeyListener);
			searchKeyListener = null;
		}
	}

	/** Send a kit interaction to the server as a public-chat token it intercepts + suppresses. */
	void sendAction(String action)
	{
		final String msg = "::lofkit " + action;
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, msg, 0, 0, 0, -1));
	}

	/**
	 * Suppress the native HOVER menu while the cursor is over the window: it is rebuilt every
	 * client tick from what's UNDER the window, so without this the top-left hint reads
	 * "Walk here" / "Attack ..." for the world behind the editor while you hover an item.
	 * The open right-click menu is left alone (onMenuOpened owns that moment).
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!overlay.isShowing() || client.isMenuOpen())
		{
			return;
		}
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m == null || overlay.hitTest(new java.awt.Point(m.getX(), m.getY())) == LofKitOverlay.OUTSIDE)
		{
			return;
		}
		client.setMenuEntries(new MenuEntry[0]);
	}

	/**
	 * Native right-click menus over the kit editor (bank mode): palette tiles get
	 * Withdraw/Equip(/quantities), kit-inventory slots get Equip/Deposit, doll slots get Remove.
	 * Runs on the client thread, so live varp + item-definition reads are safe here; the
	 * slot-under-cursor comes from the overlay's cached hit-test (LofShopTabs/Companions pattern).
	 * Right-clicks over the window chrome collapse the menu to Cancel so "Walk here" can't fire
	 * through the window.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!overlay.isShowing())
		{
			return;
		}
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m == null)
		{
			return;
		}
		final int hit = overlay.hitTest(new java.awt.Point(m.getX(), m.getY()));
		if (hit == LofKitOverlay.OUTSIDE)
		{
			return; // not over the window — the world menu is none of our business
		}
		if (!overlay.isBank())
		{
			// Training/LMS: no custom menus (yet), but the world menu must not fire through
			// the window (the LofShopTabs pattern).
			client.setMenuEntries(new MenuEntry[0]);
			return;
		}

		// Keep only the base Cancel entry (index 0 renders last), then inject ours. Entries are
		// created in REVERSE display order — the last created renders at the top.
		final MenuEntry[] entries = client.getMenuEntries();
		client.setMenuEntries(entries.length > 0 ? new MenuEntry[] { entries[0] } : entries);

		if (hit >= LofKitOverlay.DD_ITEM_BASE)
		{
			return; // dropdown rows: left-click only
		}
		if (hit >= LofKitOverlay.PAL_BASE)
		{
			paletteMenu(hit - LofKitOverlay.PAL_BASE);
		}
		else if (hit >= LofKitOverlay.INV_BASE && hit < LofKitOverlay.BOOK_BASE)
		{
			invMenu(hit - LofKitOverlay.INV_BASE);
		}
		else if (hit >= LofKitOverlay.EQUIP_BASE && hit < LofKitOverlay.INV_BASE)
		{
			dollMenu(hit - LofKitOverlay.EQUIP_BASE);
		}
	}

	/** Bank palette tile: Withdraw (the left-click default) + Equip + quantity variants. */
	private void paletteMenu(int idx)
	{
		final int id = overlay.palItemIdAt(idx);
		if (id <= 0)
		{
			return;
		}
		final int bankQty = Math.max(1, overlay.palQtyAt(idx));
		final ItemComposition c = itemManager.getItemComposition(id);
		final String target = "<col=ff9040>" + c.getName() + "</col>";
		final int cat = LofKitItems.category(c);
		final boolean stackable = c.isStackable();
		// Quantity options only where multiples make sense: stackables and consumables.
		if (stackable || cat == LofKitItems.CAT_FOOD || cat == LofKitItems.CAT_POTION)
		{
			addOption("Withdraw-All", target, "ai " + id + " " + bankQty);
			addOption("Withdraw-X", target, "aix " + id);
			addOption("Withdraw-10", target, "ai " + id + " 10");
			addOption("Withdraw-5", target, "ai " + id + " 5");
		}
		if (LofKitItems.equipable(c))
		{
			// A stackable equipable (ammo) equips its whole bank stack in one go.
			addOption("Equip", target, "ae " + id + (stackable ? " " + bankQty : ""));
		}
		addOption("Withdraw", target, "ai " + id + " 1");
	}

	/** Kit-inventory slot: Equip for wearable gear (the left-click default), Deposit for all. */
	private void invMenu(int slot)
	{
		final int packed = client.getVarpValue(LofKitOverlay.SLOT_VARP_BASE + LofKitOverlay.EQUIP_SLOTS + slot);
		final int id = packed & 0xFFFF;
		if (id <= 0)
		{
			return;
		}
		final ItemComposition c = itemManager.getItemComposition(id);
		final String target = "<col=ff9040>" + c.getName() + "</col>";
		addOption("Deposit", target, "ri " + slot);
		if (LofKitItems.equipable(c))
		{
			addOption("Equip", target, "eq " + slot);
		}
	}

	/** Doll slot: Remove (same as the left-click). */
	private void dollMenu(int idx)
	{
		final int packed = client.getVarpValue(LofKitOverlay.SLOT_VARP_BASE + idx);
		final int id = packed & 0xFFFF;
		if (id <= 0)
		{
			return;
		}
		addOption("Remove", target(id), "re " + idx);
	}

	private String target(int id)
	{
		return "<col=ff9040>" + itemManager.getItemComposition(id).getName() + "</col>";
	}

	private void addOption(String option, String target, String action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> sendAction(action));
	}

	/** Tag for the server's kit-name channel (must match KitEditor.NAMES_PREFIX). */
	private static final String NAMES_PREFIX = "~LOFKITN~";

	/**
	 * The kit-NAME feed: varps can't carry text, so the dropdown's labels arrive as a hidden chat
	 * line `~LOFKITN~<current>|<slot0>|<slot1>|<slot2>` (empty = that save slot is empty). Parsed
	 * here, handed to the overlay, and deleted from the chatbox (the companions-panel pattern —
	 * matched by CONTENT, not chat type, which transport quirks can rewrite).
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final String msg = event.getMessage();
		if (msg == null || !msg.startsWith(NAMES_PREFIX))
		{
			return;
		}
		final String[] parts = msg.substring(NAMES_PREFIX.length()).split("\\|", -1);
		final String[] saved = new String[3];
		for (int i = 0; i < 3; i++)
		{
			saved[i] = parts.length > i + 1 ? parts[i + 1] : "";
		}
		overlay.setKitNames(parts.length > 0 ? parts[0] : "", saved);
		// Delete the line from the chatbox so it stays invisible.
		final MessageNode node = event.getMessageNode();
		final ChatLineBuffer buffer = client.getChatLineMap().get(event.getType().getType());
		if (buffer != null && node != null)
		{
			buffer.removeMessageNode(node);
		}
	}
}

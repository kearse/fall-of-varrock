/*
 * Fall of Varrock — Kit editor overlay (renderer + hit-testing).
 *
 * The LMS-style loadout screen: equipment paper-doll + full 28-slot inventory + item palette +
 * presets + three per-account save slots + spellbook (and sparring-bot difficulty in training
 * mode). Drawn client-side like the duel rules screen (cache interfaces crash our client).
 *
 * The server (content/kits/KitEditor) owns the state and publishes it as varps: control varp 4640
 * (open/mode/book/diff/saved-slots) and one varp per kit slot (4641+i, itemId | qty<<16) — 11 worn
 * slots then 28 inventory slots. Item tiles are REAL item sprites via ItemManager. Clicks go back
 * as "::kit <action>". Slot order and the training armoury MUST match the server.
 */
package net.runelite.client.plugins.lofkit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofKitOverlay extends Overlay
{
	/** Must match server KitEditor. */
	static final int CONTROL_VARP = 4640;
	static final int SLOT_VARP_BASE = 4641;
	static final int EQUIP_SLOTS = 11;
	static final int INV_SIZE = 28;

	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int ACTION = 2;      // Start bout (training) / Load kit (bank)
	static final int PAGE_PREV = 3;
	static final int PAGE_NEXT = 4;
	static final int CURRENT = 5;      // copy worn gear + inventory into the editor (bank mode)
	static final int DD_HEAD = 6;      // the kit-name dropdown head (click = open/close; dbl-click = rename)
	static final int SAVE_BTN = 7;     // save the current build under its name
	static final int SEARCH_BTN = 8;   // the armoury search bar (opens the chatbox item finder)
	static final int EQUIP_BASE = 100; // + doll index (0..10)
	static final int INV_BASE = 140;   // + inventory slot (0..27)
	static final int BOOK_BASE = 330;  // + 0 std, 1 ancients, 2 lunar
	static final int DIFF_BASE = 340;  // + 0 easy, 1 medium, 2 hard
	static final int TAB_BASE = 350;   // + armoury tab (0..5)
	static final int CAT_BASE = 360;   // + bank-browser category tab (0..4, client-side only)
	static final int PAL_BASE = 400;   // + visible palette index
	// Dropdown list rows (only hit while the list is open): 0-1 presets, 2-4 saved kits, 5 = new kit.
	static final int DD_ITEM_BASE = 500;
	static final int DD_ITEM_NEW = DD_ITEM_BASE + 5;

	/** Doll slot labels, in server KitEditor.SLOT_IDS order. */
	private static final String[] SLOTS = { "Head", "Cape", "Neck", "Weap", "Body", "Shld", "Legs", "Hand", "Foot", "Ring", "Ammo" };
	private static final int[] DOLL_COL = { 1, 0, 1, 0, 1, 2, 1, 0, 1, 2, 2 };
	private static final int[] DOLL_ROW = { 0, 1, 1, 2, 2, 2, 3, 4, 4, 4, 1 };

	/** LMS mode: tabs are the LmsKits categories; each tile is a choice's representative item —
	 *  MUST match the server's LmsKits reps (order = choice order, for the selected-index bits). */
	private static final String[] LMS_TABS = { "Armour", "Weapon", "Spec", "Magic", "Food" };
	private static final int[][] LMS_CHOICES = {
		{ ItemID.FIGHTER_TORSO, ItemID.BLACK_DHIDE_BODY, ItemID.MYSTIC_ROBE_TOP, ItemID.HELM_OF_NEITIZNOT },
		{ ItemID.ABYSSAL_WHIP, ItemID.MAGIC_SHORTBOW, ItemID.ANCIENT_STAFF },
		{ ItemID.DRAGON_DAGGER, ItemID.GRANITE_MAUL },
		{ ItemID.BLOOD_RUNE, ItemID.ASTRAL_RUNE, ItemID.LAW_RUNE },
		{ ItemID.SARADOMIN_BREW4, ItemID.SHARK },
	};
	/** The training armoury in POPULARITY order (the search box's default view) — MUST match the
	 *  server's KitArmoury POOL_DEFS order. Typing in the search bar opens the native chatbox item
	 *  finder; this grid is what you browse before searching. */
	private static final int[] ARMOURY_POPULAR = {
		ItemID.ARMADYL_GODSWORD, ItemID.GRANITE_MAUL, ItemID.DRAGON_CLAWS,
		ItemID.DRAGON_DAGGER, ItemID.ABYSSAL_WHIP, ItemID.DHAROKS_GREATAXE,
		ItemID.DHAROKS_HELM, ItemID.DHAROKS_PLATEBODY, ItemID.DHAROKS_PLATELEGS,
		ItemID.MAGIC_SHORTBOW, ItemID.ANCIENT_STAFF,
		ItemID.SARADOMIN_BREW4, ItemID.SUPER_RESTORE4, ItemID.COOKED_KARAMBWAN,
		ItemID.SHARK, ItemID.ANGLERFISH, ItemID.SUPER_COMBAT_POTION4,
		ItemID.RANGING_POTION4,
		ItemID.HELM_OF_NEITIZNOT, ItemID.NEITIZNOT_FACEGUARD, ItemID.FIGHTER_TORSO,
		ItemID.BANDOS_CHESTPLATE, ItemID.BANDOS_TASSETS, ItemID.FIRE_CAPE,
		ItemID.INFERNAL_CAPE, ItemID.AMULET_OF_TORTURE, ItemID.AMULET_OF_FURY,
		ItemID.DRAGON_DEFENDER, ItemID.AVERNIC_DEFENDER, ItemID.RUNE_DEFENDER,
		ItemID.BERSERKER_HELM, ItemID.BARROWS_GLOVES, ItemID.FEROCIOUS_GLOVES,
		ItemID.DRAGON_BOOTS, ItemID.PRIMORDIAL_BOOTS,
		ItemID.BERSERKER_RING, ItemID.BERSERKER_RING_I,
		ItemID.MAGIC_SHORTBOW_I, ItemID.RUNE_ARROW, ItemID.DRAGON_ARROW,
		ItemID.KARILS_COIF, ItemID.BLACK_DHIDE_BODY, ItemID.BLACK_DHIDE_CHAPS,
		ItemID.BLACK_DHIDE_VAMBRACES, ItemID.NECKLACE_OF_ANGUISH, ItemID.ARCHERS_RING,
		ItemID.KODAI_WAND, ItemID.ANCESTRAL_HAT, ItemID.ANCESTRAL_ROBE_TOP,
		ItemID.ANCESTRAL_ROBE_BOTTOM, ItemID.MYSTIC_HAT, ItemID.MYSTIC_ROBE_TOP,
		ItemID.MYSTIC_ROBE_BOTTOM, ItemID.OCCULT_NECKLACE, ItemID.TORMENTED_BRACELET,
		ItemID.ETERNAL_BOOTS, ItemID.SEERS_RING,
		ItemID.ASTRAL_RUNE, ItemID.DEATH_RUNE, ItemID.EARTH_RUNE,
		ItemID.WATER_RUNE, ItemID.BLOOD_RUNE,
	};

	// Window geometry. Wider than the duel modal (three columns); in fixed mode it overlaps the
	// inventory column — acceptable, the kit IS your inventory while this screen is up.
	// Fits the fixed-mode viewport (512×334): 512 wide × 324 tall, so the editor sits centred in the
	// game viewport and clears the chat like the standard modals. Slots are smaller than before and the
	// item icons are drawn scaled-to-slot (itemSlot) so the 7-row inventory + palette still fit.
	static final int WIN_W = 512;
	static final int WIN_H = 324;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int PRESET_Y = TITLE_H + 6;
	private static final int PRESET_H = 20;
	private static final int LABEL_Y = TITLE_H + 34;
	private static final int COLS_TOP = TITLE_H + 40;
	private static final int COL_GAP = 30; // spacing between the doll / inventory / palette columns
	private static final int DOLL_X = PAD;
	private static final int DOLL_SZ = 34;
	private static final int DOLL_GAP = 4;
	private static final int INV_X = PAD + 3 * (DOLL_SZ + DOLL_GAP) + COL_GAP;
	private static final int INV_SZ = 28;
	private static final int INV_GAP = 2;
	private static final int PAL_X = INV_X + 4 * (INV_SZ + INV_GAP) + COL_GAP;
	private static final int PAL_SZ = 30;
	private static final int PAL_GAP = 2;
	private static final int PAL_COLS = 5;
	private static final int PAL_ROWS = 4;      // training/LMS grid (pager below)
	private static final int PAL_ROWS_BANK = 5; // bank browser grid (scrolls, no pager)
	private static final int TAB_H = 15;
	private static final int CAT_H = 15;        // bank-browser category chip height
	private static final int FOOT_H = 30;
	private static final int CHIP_H = 20;

	private final Client client;
	private final LofKitConfig config;
	private final ItemManager itemManager;

	// Client-side view state (never authoritative): armoury tab + bank palette page + dropdown.
	private int tab;
	private int bankPage;
	/** Palette ids as last rendered — read by the mouse listener off the client thread. */
	private volatile int[] palCache = new int[0];
	/** True bank stack size per visible palette tile (all 1s outside bank mode). */
	private volatile int[] palQtyCache = new int[0];
	/** Per kit-inventory slot: is the item there wearable gear? Drives the smart left-click. */
	private volatile boolean[] invEquipCache = new boolean[INV_SIZE];

	// Bank-browser view state (bank mode only, all client-side).
	/** Category tab (LofKitItems.CAT_*) — written by the mouse thread, read in render(). */
	private volatile int bankCat;
	/** Scroll offset in grid ROWS — written by the wheel listener, clamped in render(). */
	private volatile int bankScrollRows;
	/** Live search query — written by the key listener (single writer), read in render(). */
	private volatile String searchQuery = "";
	private volatile boolean searchFocused;
	// The filtered bank (ids + stack sizes), rebuilt on the client thread only when its inputs
	// change. The bank container is the client-side cache: the server pushes it when the editor
	// opens (the cache is wiped on login/hop) and again whenever the bank changes, so it stays
	// current; the server additionally clamps every real withdraw to actual stock.
	private int[] filteredIds = new int[0];
	private int[] filteredQtys = new int[0];
	private long paletteFingerprint = Long.MIN_VALUE;
	private String builtQuery = "";
	private int builtCat = -1;
	/** Kit-name dropdown open/closed — pure view state, toggled by the mouse listener. */
	private volatile boolean ddOpen;
	/** Names from the server's ~LOFKITN~ channel: the kit being edited + the three save slots. */
	private volatile String kitName = "";
	private volatile String[] savedNames = { "", "", "" };

	void setKitNames(String current, String[] saved)
	{
		kitName = current == null ? "" : current;
		savedNames = saved;
	}

	void setDropdownOpen(boolean open)
	{
		ddOpen = open;
	}

	boolean isDropdownOpen()
	{
		return ddOpen;
	}

	@Inject
	private LofKitOverlay(Client client, LofKitConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	// Computed on the CLIENT thread during render() and read by the mouse thread. Reading client
	// state off the client thread returns stale values: a bad isShowing() killed EVERY button in
	// the window, and a bad isTraining()/isLms() made the listener send the wrong command
	// (start / done / load). The click path must read ONLY these cached values. Same fix, same
	// reason, as LofShopTabsOverlay's cached showing/winRect.
	private volatile boolean showingCached;
	private volatile boolean trainingCached;
	private volatile boolean lmsCached;
	private volatile boolean bankCached;
	/** Native right-click menu open? While it is, the mouse listener must pass every click
	 *  through untouched or the menu row the player selects never fires. */
	private volatile boolean menuOpenCached;

	// Scaled placement published by render() on the client thread; the mouse thread hit-tests via this cache only.
	private volatile LofModal.Placement placement;

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isShowing()
	{
		return showingCached;
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isTraining()
	{
		return trainingCached;
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isLms()
	{
		return lmsCached;
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isBank()
	{
		return bankCached;
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isMenuOpen()
	{
		return menuOpenCached;
	}

	/** The live client-thread reads — only call from render(). */
	private boolean computeShowing()
	{
		return config.enabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& (client.getVarpValue(CONTROL_VARP) & 0x1) != 0;
	}

	private boolean computeTraining()
	{
		return ((client.getVarpValue(CONTROL_VARP) >> 1) & 0x3) == 1;
	}

	private boolean computeLms()
	{
		return ((client.getVarpValue(CONTROL_VARP) >> 1) & 0x3) == 3;
	}

	private boolean computeBank()
	{
		return ((client.getVarpValue(CONTROL_VARP) >> 1) & 0x3) == 2;
	}

	int palItemIdAt(int visibleIndex)
	{
		final int[] pal = palCache;
		return visibleIndex >= 0 && visibleIndex < pal.length ? pal[visibleIndex] : -1;
	}

	/** The bank stack size behind a visible palette tile (1 outside bank mode). */
	int palQtyAt(int visibleIndex)
	{
		final int[] qty = palQtyCache;
		return visibleIndex >= 0 && visibleIndex < qty.length ? qty[visibleIndex] : 1;
	}

	/** Is the item in kit-inventory slot i wearable gear? (cached — mouse-thread safe). */
	boolean invEquipableAt(int slot)
	{
		final boolean[] eq = invEquipCache;
		return slot >= 0 && slot < eq.length && eq[slot];
	}

	void setTab(int t)
	{
		// Tabs only exist in LMS mode now (the training armoury is the search box + popular grid).
		if (t >= 0 && t < LMS_TABS.length) { tab = t; bankPage = 0; }
	}

	void pageDelta(int d) { bankPage = Math.max(0, bankPage + d); }

	void setBankCategory(int c)
	{
		if (c >= 0 && c <= LofKitItems.CAT_OTHER) { bankCat = c; bankScrollRows = 0; }
	}

	boolean isSearchFocused() { return searchFocused; }

	void setSearchFocused(boolean focused) { searchFocused = focused; }

	void searchAppend(char c)
	{
		final String q = searchQuery;
		if (q.length() < 24) { searchQuery = q + c; bankScrollRows = 0; }
	}

	void searchBackspace()
	{
		final String q = searchQuery;
		if (!q.isEmpty()) { searchQuery = q.substring(0, q.length() - 1); bankScrollRows = 0; }
	}

	/** Wheel over the bank grid scrolls it; returns true when consumed (pattern: LofTeleports). */
	boolean handleScroll(Point canvas, int rotation)
	{
		if (!isShowing() || !isBank() || isMenuOpen()) return false;
		final LofModal.Placement place = placement;
		if (place == null) return false;
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		final int top = palTop(oy);
		if (!new Rectangle(ox + PAL_X, top, PAL_COLS * (PAL_SZ + PAL_GAP), PAL_ROWS_BANK * (PAL_SZ + PAL_GAP)).contains(p))
		{
			return false;
		}
		// Clamped properly against the filtered row count in render(); loosely bounded here.
		bankScrollRows = Math.max(0, bankScrollRows + rotation);
		return true;
	}

	// Placement single-sourced in LofModal (§6A). The editor is wider than the fixed-mode viewport,
	// so LofModal centres it on the whole canvas (its width can't clear the inventory column anyway).
	private int originX() { return LofModal.originX(client, WIN_W); }
	private int originY() { return LofModal.originY(client, WIN_H); }

	// ── geometry ──

	private Rectangle closeRect(int ox, int oy) { return new Rectangle(ox + WIN_W - 30, oy + 8, 22, 22); }

	// The kit row: one NAMED-kit dropdown (replaces the old preset + numbered-save-slot chips),
	// a Save button, and (bank mode) the "Wearing" chip. Single click opens the list; double-click
	// the title renames the kit; "+ New kit..." starts an empty build and asks for its name.
	private Rectangle ddHeadRect(int ox, int oy) { return new Rectangle(ox + PAD + 28, oy + PRESET_Y, 156, PRESET_H); }
	private Rectangle saveRect(int ox, int oy) { return new Rectangle(ox + PAD + 190, oy + PRESET_Y, 46, PRESET_H); }
	private static final int DD_ROW_H = 16;
	private Rectangle ddItemRect(int ox, int oy, int i) // list row i (0..5), below the head
	{
		final Rectangle h = ddHeadRect(ox, oy);
		return new Rectangle(h.x, h.y + h.height + 1 + i * DD_ROW_H, h.width, DD_ROW_H);
	}

	private Rectangle currentRect(int ox, int oy) // bank mode: copy the real worn+carried setup
	{
		return new Rectangle(ox + PAD + 402, oy + PRESET_Y, 62, PRESET_H);
	}

	private Rectangle dollRect(int ox, int oy, int i)
	{
		final int x = ox + DOLL_X + DOLL_COL[i] * (DOLL_SZ + DOLL_GAP);
		final int y = oy + COLS_TOP + DOLL_ROW[i] * (DOLL_SZ + DOLL_GAP);
		return new Rectangle(x, y, DOLL_SZ, DOLL_SZ);
	}

	private Rectangle invRect(int ox, int oy, int i)
	{
		final int x = ox + INV_X + (i % 4) * (INV_SZ + INV_GAP);
		final int y = oy + COLS_TOP + (i / 4) * (INV_SZ + INV_GAP);
		return new Rectangle(x, y, INV_SZ, INV_SZ);
	}

	private Rectangle tabRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + PAL_X + (i % 3) * 60, oy + COLS_TOP + (i / 3) * (TAB_H + 2), 54, TAB_H);
	}

	/** The SEARCH bar: a live filter field in bank mode, the chatbox item finder in training. */
	private Rectangle searchRect(int ox, int oy) { return new Rectangle(ox + PAL_X, oy + COLS_TOP, 5 * (PAL_SZ + PAL_GAP) - PAL_GAP, 16); }

	/** Bank-browser category chip i (All/Gear/Food/Pot/Misc), on a rail under the search bar. */
	private Rectangle catRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + PAL_X + i * (PAL_SZ + PAL_GAP), oy + COLS_TOP + 19, PAL_SZ, CAT_H);
	}

	/** Palette origin: below the category rail (bank), the search bar (training) or the tabs (LMS). */
	private int palTop(int oy)
	{
		if (isLms()) return oy + COLS_TOP + 2 * (TAB_H + 2) + 6;
		if (isBank()) return oy + COLS_TOP + 19 + CAT_H + 4;
		return oy + COLS_TOP + 22;
	}

	/** Visible palette grid rows for the current mode. */
	private int palRows() { return isBank() ? PAL_ROWS_BANK : PAL_ROWS; }

	private Rectangle palRect(int ox, int oy, int i)
	{
		final int x = ox + PAL_X + (i % PAL_COLS) * (PAL_SZ + PAL_GAP);
		final int y = palTop(oy) + (i / PAL_COLS) * (PAL_SZ + PAL_GAP);
		return new Rectangle(x, y, PAL_SZ, PAL_SZ);
	}

	private Rectangle pagePrevRect(int ox, int oy) { return new Rectangle(ox + PAL_X, palTop(oy) + PAL_ROWS * (PAL_SZ + PAL_GAP) + 4, 30, 18); }
	private Rectangle pageNextRect(int ox, int oy) { return new Rectangle(ox + PAL_X + 150, palTop(oy) + PAL_ROWS * (PAL_SZ + PAL_GAP) + 4, 30, 18); }

	private Rectangle bookRect(int ox, int oy, int i) { return new Rectangle(ox + PAD + 50 + i * 44, oy + WIN_H - FOOT_H, 40, CHIP_H); }
	private Rectangle diffRect(int ox, int oy, int i) { return new Rectangle(ox + PAD + 226 + i * 40, oy + WIN_H - FOOT_H, 36, CHIP_H); }
	private Rectangle actionRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 108, oy + WIN_H - FOOT_H - 4, 108, 28); }

	int hitTest(Point canvas)
	{
		if (!isShowing()) return OUTSIDE;
		final LofModal.Placement place = placement;
		if (place == null) return OUTSIDE;
		final Point p = place.toLocal(canvas);
		final boolean training = isTraining(), lms = isLms();
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (closeRect(ox, oy).contains(p)) return CLOSE;
		if (actionRect(ox, oy).contains(p)) return ACTION;
		if (!lms)
		{
			if (ddHeadRect(ox, oy).contains(p)) return DD_HEAD;
			// The open dropdown list draws OVER the doll — its rows must win the hit-test.
			if (ddOpen)
			{
				for (int i = 0; i < 6; i++) if (ddItemRect(ox, oy, i).contains(p)) return DD_ITEM_BASE + i;
			}
			if (saveRect(ox, oy).contains(p)) return SAVE_BTN;
			if (!training && currentRect(ox, oy).contains(p)) return CURRENT;
			// In LMS mode the doll + inventory are a read-only preview of the category picks.
			for (int i = 0; i < EQUIP_SLOTS; i++) if (dollRect(ox, oy, i).contains(p)) return EQUIP_BASE + i;
			for (int i = 0; i < INV_SIZE; i++) if (invRect(ox, oy, i).contains(p)) return INV_BASE + i;
			for (int i = 0; i < 3; i++) if (bookRect(ox, oy, i).contains(p)) return BOOK_BASE + i;
		}
		if (lms)
		{
			for (int i = 0; i < LMS_TABS.length; i++) if (tabRect(ox, oy, i).contains(p)) return TAB_BASE + i;
		}
		else if (training)
		{
			if (searchRect(ox, oy).contains(p)) return SEARCH_BTN;
			if (pagePrevRect(ox, oy).contains(p)) return PAGE_PREV;
			if (pageNextRect(ox, oy).contains(p)) return PAGE_NEXT;
		}
		else
		{
			// Bank browser: live search field + category rail; the grid scrolls (no pager).
			if (searchRect(ox, oy).contains(p)) return SEARCH_BTN;
			for (int i = 0; i <= LofKitItems.CAT_OTHER; i++) if (catRect(ox, oy, i).contains(p)) return CAT_BASE + i;
		}
		if (training)
		{
			for (int i = 0; i < 3; i++) if (diffRect(ox, oy, i).contains(p)) return DIFF_BASE + i;
		}
		final int[] pal = palCache;
		for (int i = 0; i < pal.length; i++) if (palRect(ox, oy, i).contains(p)) return PAL_BASE + i;
		return INSIDE;
	}

	// ── palette contents ──

	/** The palette page on screen: the tab's armoury (training), the tab's LMS category choices
	 *  (LMS mode), or the filtered + scrolled bank window (bank mode; also fills [palQtyCache]). */
	private int[] buildPalette()
	{
		if (isTraining())
		{
			// The armoury, popular-first, paged (same paging chips as before).
			final int pageSize = PAL_COLS * PAL_ROWS;
			final int maxPage = Math.max(0, (ARMOURY_POPULAR.length - 1) / pageSize);
			if (bankPage > maxPage) bankPage = maxPage;
			final int from = bankPage * pageSize;
			final int count = Math.max(0, Math.min(pageSize, ARMOURY_POPULAR.length - from));
			final int[] page = new int[count];
			System.arraycopy(ARMOURY_POPULAR, from, page, 0, count);
			palQtyCache = new int[0];
			return page;
		}
		if (isLms())
		{
			palQtyCache = new int[0];
			return LMS_CHOICES[Math.min(tab, LMS_CHOICES.length - 1)];
		}
		// Bank browser: category tab + live search over the client's cached bank container,
		// then the scroll window over the filtered list.
		rebuildBankFilter();
		final int visible = PAL_COLS * PAL_ROWS_BANK;
		final int totalRows = (filteredIds.length + PAL_COLS - 1) / PAL_COLS;
		final int maxScroll = Math.max(0, totalRows - PAL_ROWS_BANK);
		int scroll = bankScrollRows;
		if (scroll > maxScroll) { scroll = maxScroll; bankScrollRows = maxScroll; }
		final int from = scroll * PAL_COLS;
		final int count = Math.max(0, Math.min(visible, filteredIds.length - from));
		final int[] page = new int[count];
		final int[] qtys = new int[count];
		System.arraycopy(filteredIds, from, page, 0, count);
		System.arraycopy(filteredQtys, from, qtys, 0, count);
		palQtyCache = qtys;
		return page;
	}

	/** Re-filter the cached bank into [filteredIds]/[filteredQtys] when its inputs changed.
	 *  Client thread only (composition lookups). */
	private void rebuildBankFilter()
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		final net.runelite.api.Item[] items = bank == null ? new net.runelite.api.Item[0] : bank.getItems();
		long fp = items.length;
		for (net.runelite.api.Item it : items)
		{
			if (it != null) fp = fp * 31 + it.getId() * 31L + it.getQuantity();
		}
		final String q = searchQuery.toLowerCase();
		final int cat = bankCat;
		if (fp == paletteFingerprint && q.equals(builtQuery) && cat == builtCat) return;
		paletteFingerprint = fp;
		builtQuery = q;
		builtCat = cat;
		final int[] ids = new int[items.length];
		final int[] qtys = new int[items.length];
		int n = 0;
		for (net.runelite.api.Item it : items)
		{
			if (it == null || it.getId() <= 0 || it.getQuantity() <= 0) continue; // qty<=0 = placeholder
			try
			{
				final net.runelite.api.ItemComposition c = itemManager.getItemComposition(it.getId());
				if (cat != LofKitItems.CAT_ALL && LofKitItems.category(c) != cat) continue;
				if (!q.isEmpty() && !c.getName().toLowerCase().contains(q)) continue;
			}
			catch (Exception e)
			{
				if (cat != LofKitItems.CAT_ALL || !q.isEmpty()) continue;
			}
			ids[n] = it.getId();
			qtys[n] = it.getQuantity();
			n++;
		}
		filteredIds = java.util.Arrays.copyOf(ids, n);
		filteredQtys = java.util.Arrays.copyOf(qtys, n);
	}

	// ── rendering ──

	@Override
	public Dimension render(Graphics2D g)
	{
		// Publish the gates for the mouse thread in the same pass that draws them.
		final boolean showing = computeShowing();
		showingCached = showing;
		trainingCached = computeTraining();
		lmsCached = computeLms();
		bankCached = computeBank();
		menuOpenCached = client.isMenuOpen();
		if (!showing) return null;

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;

		final int control = client.getVarpValue(CONTROL_VARP);
		final int mode = (control >> 1) & 0x3;
		final boolean training = mode == 1;
		final boolean lms = mode == 3;
		final int book = (control >> 3) & 0x3;
		final int diff = (control >> 5) & 0x3;
		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());
		String hover = null;

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);

		// header
		final Shape clip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(clip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);
		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null) { g.drawImage(logo, ox + 12, oy + 5, 28, 28, null); titleX = ox + 46; }
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Kit Editor", titleX, oy + 25, LofTheme.GOLD);
		// Close ✕ drawn as two strokes (not a glyph — the RuneScape font has no ✕, so a text ✕ tofus).
		final Rectangle xr = closeRect(ox, oy);
		g.setColor(xr.contains(mouse) ? LofTheme.LAVA : LofTheme.TEXT_DIM);
		final Stroke oldX = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(xr.x + 7, xr.y + 7, xr.x + xr.width - 8, xr.y + xr.height - 8);
		g.drawLine(xr.x + xr.width - 8, xr.y + 7, xr.x + 7, xr.y + xr.height - 8);
		g.setStroke(oldX);

		// the named-kit dropdown + Save (not in LMS mode — LMS has exactly one kit, the category picks)
		g.setFont(FontManager.getRunescapeFont());
		if (!lms)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "KIT", ox + PAD, oy + PRESET_Y + 14, LofTheme.GOLD_DIM);
			g.setFont(FontManager.getRunescapeFont());
			final Rectangle hd = ddHeadRect(ox, oy);
			ddChip(g, hd, kitName.isEmpty() ? "Unnamed kit" : kitName, hd.contains(mouse));
			if (hd.contains(mouse)) hover = "Pick a kit - double-click to rename";
			final Rectangle sv = saveRect(ox, oy);
			chip(g, sv, "Save", false, sv.contains(mouse));
			if (sv.contains(mouse)) hover = "Save this build under its name";
			if (!training)
			{
				final Rectangle cr = currentRect(ox, oy);
				chip(g, cr, "Wearing", false, cr.contains(mouse));
				if (cr.contains(mouse)) hover = "Copy what you're wearing & carrying into the editor";
			}
		}
		else
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "Your Last Man Standing spawn kit — pick one option per tab.",
				ox + PAD + 2, oy + PRESET_Y + 14, LofTheme.TEXT_DIM);
			g.setFont(FontManager.getRunescapeFont());
		}

		// section labels
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, lms ? "YOU'LL WEAR" : "WORN GEAR", ox + DOLL_X + 2, oy + LABEL_Y, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, lms ? "YOU'LL CARRY" : "INVENTORY", ox + INV_X + 2, oy + LABEL_Y, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g,
			training ? "ARMOURY" : lms ? "KIT OPTIONS — ONE PER TAB" : "YOUR BANK",
			ox + PAL_X + 2, oy + LABEL_Y, LofTheme.GOLD_DIM);

		final boolean bank = !training && !lms;

		// worn gear paper-doll (varps 4641..4651)
		for (int i = 0; i < EQUIP_SLOTS; i++)
		{
			final int packed = client.getVarpValue(SLOT_VARP_BASE + i);
			final Rectangle rc = dollRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			itemSlot(g, rc, packed, SLOTS[i], hov && !lms);
			if (hov && !lms && (packed & 0xFFFF) != 0) hover = "Remove " + itemName(packed & 0xFFFF);
		}

		// inventory grid (varps 4652..4679) — in bank mode the smart left-click needs to know
		// which slots hold wearable gear (equip) vs consumables/supplies (deposit).
		final boolean[] invEquip = new boolean[INV_SIZE];
		for (int i = 0; i < INV_SIZE; i++)
		{
			final int packed = client.getVarpValue(SLOT_VARP_BASE + EQUIP_SLOTS + i);
			final int id = packed & 0xFFFF;
			if (bank && id > 0)
			{
				try
				{
					invEquip[i] = LofKitItems.equipable(itemManager.getItemComposition(id));
				}
				catch (Exception ignored)
				{
				}
			}
			final Rectangle rc = invRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			itemSlot(g, rc, packed, null, hov && !lms);
			if (hov && !lms && id != 0)
			{
				hover = bank
					? (invEquip[i] ? "Equip " : "Deposit ") + itemName(id)
					: "Remove " + itemName(id);
			}
		}
		invEquipCache = invEquip;

		// palette: search bar + popular-first armoury (training) / searched bank (bank mode) /
		// category tabs (LMS)
		final int[] pal = buildPalette();
		palCache = pal;
		if (lms)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			for (int i = 0; i < LMS_TABS.length; i++)
			{
				final Rectangle rc = tabRect(ox, oy, i);
				chip(g, rc, LMS_TABS[i], i == tab, rc.contains(mouse));
			}
		}
		else
		{
			// The search bar: a LIVE filter field in bank mode (type to narrow your bank), or a
			// click-to-open chatbox item finder in training mode.
			final Rectangle sr = searchRect(ox, oy);
			final boolean focused = bank && searchFocused;
			final String query = bank ? searchQuery : "";
			g.setColor(sr.contains(mouse) || focused ? LofTheme.ROW_HOVER : new Color(0, 0, 0, 90));
			g.fillRoundRect(sr.x, sr.y, sr.width, sr.height, 6, 6);
			g.setColor(LofTheme.alpha(LofTheme.GOLD, focused ? 230 : sr.contains(mouse) ? 200 : 110));
			final Stroke oldS = g.getStroke();
			g.setStroke(new BasicStroke(1f));
			g.drawRoundRect(sr.x, sr.y, sr.width - 1, sr.height - 1, 6, 6);
			g.setStroke(oldS);
			// magnifier glass drawn as strokes (the RuneScape font has no such glyph)
			g.setColor(LofTheme.GOLD_DIM);
			g.drawOval(sr.x + 5, sr.y + 4, 6, 6);
			g.drawLine(sr.x + 10, sr.y + 10, sr.x + 13, sr.y + 13);
			g.setFont(FontManager.getRunescapeSmallFont());
			if (query.isEmpty() && !focused)
			{
				LofTheme.shadowText(g, training ? "Search the armoury..." : "Search your bank...",
					sr.x + 18, sr.y + 12, LofTheme.TEXT_DIM);
			}
			else
			{
				// Query text + a caret while typing (blink driven by the client's game cycle).
				LofTheme.shadowText(g, query, sr.x + 18, sr.y + 12, LofTheme.TEXT);
				if (focused && (client.getGameCycle() % 40) < 20)
				{
					final int cx = sr.x + 19 + g.getFontMetrics().stringWidth(query);
					g.setColor(LofTheme.GOLD);
					g.drawLine(cx, sr.y + 3, cx, sr.y + sr.height - 4);
				}
			}
			if (sr.contains(mouse))
			{
				hover = bank ? "Type to filter your bank" : "Search for an item to add to the kit";
			}

			// Bank browser: the category rail under the search field.
			if (bank)
			{
				final String[] cats = { "All", "Gear", "Food", "Pot", "Misc" };
				final String[] catHints = { "Everything", "Weapons & armour", "Food", "Potions", "Everything else" };
				for (int i = 0; i < cats.length; i++)
				{
					final Rectangle rc = catRect(ox, oy, i);
					final boolean hov = rc.contains(mouse);
					g.setFont(FontManager.getRunescapeSmallFont());
					chip(g, rc, cats[i], bankCat == i, hov);
					if (hov) hover = catHints[i];
				}
			}
		}
		final int lmsSelected = lms ? (control >> (10 + 2 * Math.min(tab, LMS_CHOICES.length - 1))) & 0x3 : -1;
		final int[] palQty = palQtyCache;
		for (int i = 0; i < pal.length; i++)
		{
			final Rectangle rc = palRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			// Bank tiles show the TRUE stack size (a kit slot still caps at 65535 on the wire).
			final int qty = bank && i < palQty.length ? palQty[i] : 1;
			itemSlot(g, rc, pal[i], qty, null, hov);
			if (lms && i == lmsSelected)
			{
				g.setColor(LofTheme.GOLD);
				g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
			}
			if (hov)
			{
				hover = (lms ? "Pick " : bank ? "Withdraw " : "Add ") + itemName(pal[i])
					+ (bank ? " - right-click for options" : "");
			}
		}
		if (training)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			chip(g, pagePrevRect(ox, oy), "<", false, pagePrevRect(ox, oy).contains(mouse));
			chip(g, pageNextRect(ox, oy), ">", false, pageNextRect(ox, oy).contains(mouse));
			LofTheme.shadowText(g, "Page " + (bankPage + 1), ox + PAL_X + 78, pagePrevRect(ox, oy).y + 13, LofTheme.TEXT_DIM);
		}
		else if (bank)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			if (pal.length == 0)
			{
				// The server pushes the bank container when the editor opens, so a null container
				// only lasts until the next game tick delivers it.
				final String empty;
				if (client.getItemContainer(InventoryID.BANK) == null)
				{
					empty = "Fetching your bank...";
				}
				else if (searchQuery.isEmpty() && bankCat == LofKitItems.CAT_ALL)
				{
					empty = "Your bank is empty.";
				}
				else
				{
					empty = "Nothing matches.";
				}
				LofTheme.shadowText(g, empty, ox + PAL_X, palTop(oy) + 14, LofTheme.TEXT_DIM);
			}
			// Scrollbar along the grid's right edge (wheel scrolls; thumb is display-only).
			final int viewH = PAL_ROWS_BANK * (PAL_SZ + PAL_GAP) - PAL_GAP;
			final int totalRows = (filteredIds.length + PAL_COLS - 1) / PAL_COLS;
			final int contentH = Math.max(viewH, totalRows * (PAL_SZ + PAL_GAP) - PAL_GAP);
			LofModal.scrollbar(g, ox + PAL_X + PAL_COLS * (PAL_SZ + PAL_GAP) + 2, palTop(oy),
				viewH, contentH, bankScrollRows * (PAL_SZ + PAL_GAP));
		}

		// footer: spellbook (not LMS — the magic pack owns it), difficulty (training), action button
		if (!lms)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "BOOK", ox + PAD, oy + WIN_H - FOOT_H + 14, LofTheme.GOLD_DIM);
			g.setFont(FontManager.getRunescapeFont());
			final String[] books = { "Std", "Anc", "Lun" };
			for (int i = 0; i < 3; i++)
			{
				chip(g, bookRect(ox, oy, i), books[i], book == i, bookRect(ox, oy, i).contains(mouse));
			}
		}
		else
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			final String[] bookNames = { "Standard", "Ancients", "Lunar" };
			LofTheme.shadowText(g, "SPELLBOOK: " + bookNames[Math.min(book, 2)] + " (set by your magic pack)",
				ox + PAD, oy + WIN_H - FOOT_H + 14, LofTheme.GOLD_DIM);
		}
		if (training)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "BOT", ox + PAD + 196, oy + WIN_H - FOOT_H + 14, LofTheme.GOLD_DIM);
			g.setFont(FontManager.getRunescapeFont());
			final String[] diffs = { "Easy", "Med", "Hard" };
			for (int i = 0; i < 3; i++)
			{
				chip(g, diffRect(ox, oy, i), diffs[i], diff == i, diffRect(ox, oy, i).contains(mouse));
			}
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		button(g, actionRect(ox, oy), training ? "Start bout" : lms ? "Done" : "Load kit",
			LofTheme.GOLD, false, actionRect(ox, oy).contains(mouse));

		// the OPEN kit dropdown draws LAST so its list sits over the doll column
		if (!lms && ddOpen)
		{
			final String[] names = savedNames;
			final Rectangle first = ddItemRect(ox, oy, 0);
			final Rectangle last = ddItemRect(ox, oy, 5);
			g.setColor(new Color(28, 23, 21));
			g.fillRoundRect(first.x, first.y, first.width, last.y + last.height - first.y, 6, 6);
			g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 160));
			g.drawRoundRect(first.x, first.y, first.width - 1, last.y + last.height - first.y - 1, 6, 6);
			g.setFont(FontManager.getRunescapeFont());
			final String[] presetNames = { "Dharok's", "NH Tribrid" };
			for (int i = 0; i < 6; i++)
			{
				final Rectangle rc = ddItemRect(ox, oy, i);
				final boolean hov = rc.contains(mouse);
				String label;
				Color col;
				if (i < 2)
				{
					label = presetNames[i];
					col = hov ? LofTheme.TEXT : LofTheme.TEXT_DIM;
				}
				else if (i < 5)
				{
					final int slot = i - 2;
					final boolean filled = (control & (1 << (7 + slot))) != 0;
					final String nm = (names != null && slot < names.length) ? names[slot] : "";
					label = filled ? (nm.isEmpty() ? "Kit " + (slot + 1) : nm) : "(empty slot)";
					col = filled ? (hov ? LofTheme.TEXT : LofTheme.TEXT_DIM) : LofTheme.alpha(LofTheme.TEXT_DIM, 110);
				}
				else
				{
					label = "+ New kit...";
					col = hov ? GREEN_ON : LofTheme.alpha(GREEN_ON, 190);
				}
				if (hov)
				{
					g.setColor(LofTheme.ROW_HOVER);
					g.fillRoundRect(rc.x + 2, rc.y, rc.width - 4, rc.height - 1, 4, 4);
				}
				LofTheme.shadowText(g, label, rc.x + 8, rc.y + rc.height / 2 + 5, col);
			}
		}

		// hover hint in the title bar (OSRS-style)
		if (hover != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, hover, titleX + 96, oy + 24, LofTheme.GOLD);
		}

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** One kit slot: the REAL item sprite (with its stack count) or the slot's ghost label. */
	private void itemSlot(Graphics2D g, Rectangle rc, int packed, String emptyLabel, boolean hov)
	{
		itemSlot(g, rc, packed & 0xFFFF, (packed >> 16) & 0xFFFF, emptyLabel, hov);
	}

	/** Same, with the quantity unpacked — bank tiles pass TRUE stack sizes (beyond the varp's
	 *  16-bit cap) so a large stack renders its real count with the native k/M formatting. */
	private void itemSlot(Graphics2D g, Rectangle rc, int id, int qty, String emptyLabel, boolean hov)
	{
		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		g.setColor(LofTheme.alpha(LofTheme.EMBER, hov ? 130 : 40));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
		if (id > 0)
		{
			final BufferedImage img = itemManager.getImage(id, qty, qty > 1);
			if (img != null)
			{
				// Scale the 32px sprite to the (smaller) slot so the icon fills it without spilling.
				g.drawImage(img, rc.x + 2, rc.y + 2, rc.width - 4, rc.height - 4, null);
			}
		}
		else if (emptyLabel != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, emptyLabel,
				rc.x + (rc.width - g.getFontMetrics().stringWidth(emptyLabel)) / 2,
				rc.y + rc.height / 2 + 4, LofTheme.alpha(LofTheme.TEXT_DIM, 110));
		}
	}

	private String itemName(int id)
	{
		try
		{
			return itemManager.getItemComposition(id).getName();
		}
		catch (Exception e)
		{
			return "item " + id;
		}
	}

	private static final Color GREEN_ON = new Color(110, 205, 110);

	/** The dropdown head: the current kit's name + a drawn caret (the font has no arrow glyphs). */
	private void ddChip(Graphics2D g, Rectangle rc, String label, boolean hov)
	{
		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		g.setColor(LofTheme.alpha(LofTheme.GOLD, hov ? 200 : 120));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
		LofTheme.shadowText(g, label, rc.x + 7, rc.y + rc.height / 2 + 5, LofTheme.GOLD);
		// caret: a small filled triangle at the right edge
		final int cx = rc.x + rc.width - 13, cy = rc.y + rc.height / 2 - 2;
		final int[] xs = { cx, cx + 8, cx + 4 };
		final int[] ys = { cy, cy, cy + 5 };
		g.setColor(LofTheme.GOLD_DIM);
		g.fillPolygon(xs, ys, 3);
	}

	private void chip(Graphics2D g, Rectangle rc, String label, boolean active, boolean hov)
	{
		g.setColor(active ? LofTheme.alpha(LofTheme.GOLD, 34) : (hov ? LofTheme.ROW_HOVER : LofTheme.ROW));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		if (active)
		{
			g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 200));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
		}
		final int tw = g.getFontMetrics().stringWidth(label);
		LofTheme.shadowText(g, label, rc.x + (rc.width - tw) / 2, rc.y + rc.height / 2 + 5,
			active ? LofTheme.GOLD : (hov ? LofTheme.TEXT : LofTheme.TEXT_DIM));
	}

	private static void button(Graphics2D g, Rectangle rc, String label, Color accent, boolean active, boolean hov)
	{
		g.setColor(active ? LofTheme.alpha(accent, 46) : (hov ? LofTheme.alpha(accent, 30) : new Color(255, 255, 255, 12)));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 8, 8);
		g.setColor(LofTheme.alpha(accent, hov || active ? 200 : 120));
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 8, 8);
		g.setStroke(old);
		final int tw = g.getFontMetrics().stringWidth(label);
		LofTheme.shadowText(g, label, rc.x + (rc.width - tw) / 2, rc.y + rc.height / 2 + 6, accent);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}

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
	static final int EQUIP_BASE = 100; // + doll index (0..10)
	static final int INV_BASE = 140;   // + inventory slot (0..27)
	static final int PRESET_BASE = 300; // + 0 Dharok's, 1 NH
	static final int KITLOAD_BASE = 310; // + save slot (0..2)
	static final int KITSAVE_BASE = 320; // + save slot (0..2)
	static final int BOOK_BASE = 330;  // + 0 std, 1 ancients, 2 lunar
	static final int DIFF_BASE = 340;  // + 0 easy, 1 medium, 2 hard
	static final int TAB_BASE = 350;   // + armoury tab (0..5)
	static final int PAL_BASE = 400;   // + visible palette index

	/** Doll slot labels, in server KitEditor.SLOT_IDS order. */
	private static final String[] SLOTS = { "Head", "Cape", "Neck", "Weap", "Body", "Shld", "Legs", "Hand", "Foot", "Ring", "Ammo" };
	private static final int[] DOLL_COL = { 1, 0, 1, 0, 1, 2, 1, 0, 1, 2, 2 };
	private static final int[] DOLL_ROW = { 0, 1, 1, 2, 2, 2, 3, 4, 4, 4, 1 };

	/** Training armoury — MUST match the server's KitArmoury pool, grouped for the tabs. */
	private static final String[] TABS = { "Melee", "Range", "Magic", "Armour", "Supply", "Runes" };

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
	private static final int[][] ARMOURY = {
		{ ItemID.ABYSSAL_WHIP, ItemID.DRAGON_DAGGER, ItemID.DRAGON_CLAWS, ItemID.DHAROKS_GREATAXE, ItemID.DRAGON_DEFENDER },
		{ ItemID.MAGIC_SHORTBOW, ItemID.RUNE_ARROW, ItemID.BLACK_DHIDE_BODY, ItemID.BLACK_DHIDE_CHAPS },
		{ ItemID.ANCIENT_STAFF, ItemID.MYSTIC_HAT, ItemID.MYSTIC_ROBE_TOP, ItemID.MYSTIC_ROBE_BOTTOM, ItemID.OCCULT_NECKLACE },
		{ ItemID.DHAROKS_HELM, ItemID.DHAROKS_PLATEBODY, ItemID.DHAROKS_PLATELEGS, ItemID.HELM_OF_NEITIZNOT,
			ItemID.FIGHTER_TORSO, ItemID.FIRE_CAPE, ItemID.AMULET_OF_TORTURE, ItemID.BARROWS_GLOVES,
			ItemID.DRAGON_BOOTS, ItemID.BERSERKER_RING, ItemID.BERSERKER_RING_I },
		{ ItemID.SUPER_COMBAT_POTION4, ItemID.SARADOMIN_BREW4, ItemID.SUPER_RESTORE4, ItemID.SHARK, ItemID.COOKED_KARAMBWAN },
		{ ItemID.ASTRAL_RUNE, ItemID.DEATH_RUNE, ItemID.EARTH_RUNE, ItemID.WATER_RUNE, ItemID.BLOOD_RUNE },
	};

	// Window geometry. Wider than the duel modal (three columns); in fixed mode it overlaps the
	// inventory column — acceptable, the kit IS your inventory while this screen is up.
	static final int WIN_W = 580;
	static final int WIN_H = 444;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int PRESET_Y = TITLE_H + 8;
	private static final int PRESET_H = 22;
	private static final int LABEL_Y = TITLE_H + 50;
	private static final int COLS_TOP = TITLE_H + 58;
	private static final int DOLL_X = PAD;
	private static final int DOLL_SZ = 44;
	private static final int DOLL_GAP = 6;
	private static final int INV_X = PAD + 3 * (DOLL_SZ + DOLL_GAP) + 12;
	private static final int INV_SZ = 40;
	private static final int INV_GAP = 4;
	private static final int PAL_X = INV_X + 4 * (INV_SZ + INV_GAP) + 12;
	private static final int PAL_SZ = 40;
	private static final int PAL_GAP = 2;
	private static final int PAL_COLS = 5;
	private static final int PAL_ROWS = 4;
	private static final int TAB_H = 18;
	private static final int FOOT_H = 34;
	private static final int CHIP_H = 20;

	private final Client client;
	private final LofKitConfig config;
	private final ItemManager itemManager;

	// Client-side view state (never authoritative): armoury tab + bank palette page.
	private int tab;
	private int bankPage;
	/** Palette ids as last rendered — read by the mouse listener off the client thread. */
	private volatile int[] palCache = new int[0];

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

	int palItemIdAt(int visibleIndex)
	{
		final int[] pal = palCache;
		return visibleIndex >= 0 && visibleIndex < pal.length ? pal[visibleIndex] : -1;
	}

	void setTab(int t)
	{
		if (t >= 0 && t < TABS.length) { tab = t; bankPage = 0; }
	}

	void pageDelta(int d) { bankPage = Math.max(0, bankPage + d); }

	// Placement single-sourced in LofModal (§6A). The editor is wider than the fixed-mode viewport,
	// so LofModal centres it on the whole canvas (its width can't clear the inventory column anyway).
	private int originX() { return LofModal.originX(client, WIN_W); }
	private int originY() { return LofModal.originY(client, WIN_H); }

	// ── geometry ──

	private Rectangle closeRect(int ox, int oy) { return new Rectangle(ox + WIN_W - 30, oy + 8, 22, 22); }

	private Rectangle presetRect(int ox, int oy, int i) // 0..1 presets
	{
		return new Rectangle(ox + PAD + i * 84, oy + PRESET_Y, 80, PRESET_H);
	}

	private Rectangle kitLoadRect(int ox, int oy, int i) // 0..2 saved kits
	{
		return new Rectangle(ox + PAD + 172 + i * 76, oy + PRESET_Y, 50, PRESET_H);
	}

	private Rectangle kitSaveRect(int ox, int oy, int i)
	{
		final Rectangle r = kitLoadRect(ox, oy, i);
		return new Rectangle(r.x + r.width + 2, r.y, 20, PRESET_H);
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
		return new Rectangle(ox + PAL_X + (i % 3) * 70, oy + COLS_TOP + (i / 3) * (TAB_H + 2), 66, TAB_H);
	}

	private int palTop(int oy) { return oy + COLS_TOP + 2 * (TAB_H + 2) + 6; }

	private Rectangle palRect(int ox, int oy, int i)
	{
		final int x = ox + PAL_X + (i % PAL_COLS) * (PAL_SZ + PAL_GAP);
		final int y = palTop(oy) + (i / PAL_COLS) * (PAL_SZ + PAL_GAP);
		return new Rectangle(x, y, PAL_SZ, PAL_SZ);
	}

	private Rectangle pagePrevRect(int ox, int oy) { return new Rectangle(ox + PAL_X, palTop(oy) + PAL_ROWS * (PAL_SZ + PAL_GAP) + 4, 30, 18); }
	private Rectangle pageNextRect(int ox, int oy) { return new Rectangle(ox + PAL_X + 178, palTop(oy) + PAL_ROWS * (PAL_SZ + PAL_GAP) + 4, 30, 18); }

	private Rectangle bookRect(int ox, int oy, int i) { return new Rectangle(ox + PAD + 64 + i * 52, oy + WIN_H - FOOT_H, 48, CHIP_H); }
	private Rectangle diffRect(int ox, int oy, int i) { return new Rectangle(ox + PAD + 288 + i * 46, oy + WIN_H - FOOT_H, 42, CHIP_H); }
	private Rectangle actionRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 108, oy + WIN_H - FOOT_H - 4, 108, 28); }

	int hitTest(Point p)
	{
		if (!isShowing()) return OUTSIDE;
		final boolean training = isTraining(), lms = isLms();
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (closeRect(ox, oy).contains(p)) return CLOSE;
		if (actionRect(ox, oy).contains(p)) return ACTION;
		if (!lms)
		{
			for (int i = 0; i < 2; i++) if (presetRect(ox, oy, i).contains(p)) return PRESET_BASE + i;
			for (int i = 0; i < 3; i++)
			{
				if (kitLoadRect(ox, oy, i).contains(p)) return KITLOAD_BASE + i;
				if (kitSaveRect(ox, oy, i).contains(p)) return KITSAVE_BASE + i;
			}
			// In LMS mode the doll + inventory are a read-only preview of the category picks.
			for (int i = 0; i < EQUIP_SLOTS; i++) if (dollRect(ox, oy, i).contains(p)) return EQUIP_BASE + i;
			for (int i = 0; i < INV_SIZE; i++) if (invRect(ox, oy, i).contains(p)) return INV_BASE + i;
			for (int i = 0; i < 3; i++) if (bookRect(ox, oy, i).contains(p)) return BOOK_BASE + i;
		}
		if (training || lms)
		{
			final int tabs = lms ? LMS_TABS.length : TABS.length;
			for (int i = 0; i < tabs; i++) if (tabRect(ox, oy, i).contains(p)) return TAB_BASE + i;
		}
		if (training)
		{
			for (int i = 0; i < 3; i++) if (diffRect(ox, oy, i).contains(p)) return DIFF_BASE + i;
		}
		if (!training && !lms)
		{
			if (pagePrevRect(ox, oy).contains(p)) return PAGE_PREV;
			if (pageNextRect(ox, oy).contains(p)) return PAGE_NEXT;
		}
		final int[] pal = palCache;
		for (int i = 0; i < pal.length; i++) if (palRect(ox, oy, i).contains(p)) return PAL_BASE + i;
		return INSIDE;
	}

	// ── palette contents ──

	/** The palette page on screen: the tab's armoury (training), the tab's LMS category choices
	 *  (LMS mode), or a bank page (bank mode). */
	private int[] buildPalette()
	{
		if (isTraining())
		{
			return ARMOURY[Math.min(tab, ARMOURY.length - 1)];
		}
		if (isLms())
		{
			return LMS_CHOICES[Math.min(tab, LMS_CHOICES.length - 1)];
		}
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null) return new int[0];
		final net.runelite.api.Item[] items = bank.getItems();
		final int pageSize = PAL_COLS * PAL_ROWS;
		final int[] all = new int[items.length];
		int n = 0;
		for (net.runelite.api.Item it : items)
		{
			if (it != null && it.getId() > 0 && it.getQuantity() > 0) all[n++] = it.getId();
		}
		final int maxPage = Math.max(0, (n - 1) / pageSize);
		if (bankPage > maxPage) bankPage = maxPage;
		final int from = bankPage * pageSize;
		final int count = Math.max(0, Math.min(pageSize, n - from));
		final int[] page = new int[count];
		System.arraycopy(all, from, page, 0, count);
		return page;
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
		if (!showing) return null;

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int control = client.getVarpValue(CONTROL_VARP);
		final int mode = (control >> 1) & 0x3;
		final boolean training = mode == 1;
		final boolean lms = mode == 3;
		final int book = (control >> 3) & 0x3;
		final int diff = (control >> 5) & 0x3;
		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();
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
		g.setFont(FontManager.getRunescapeBoldFont());
		final Rectangle xr = closeRect(ox, oy);
		LofTheme.shadowText(g, "✕", xr.x + 6, xr.y + 16, xr.contains(mouse) ? LofTheme.LAVA : LofTheme.TEXT_DIM);

		// preset + save-slot chips (not in LMS mode — LMS has exactly one kit, the category picks)
		g.setFont(FontManager.getRunescapeFont());
		if (!lms)
		{
			final String[] presets = { "Dharok's", "NH Tribrid" };
			for (int i = 0; i < 2; i++)
			{
				chip(g, presetRect(ox, oy, i), presets[i], false, presetRect(ox, oy, i).contains(mouse));
			}
			for (int i = 0; i < 3; i++)
			{
				final boolean filled = (control & (1 << (7 + i))) != 0;
				final Rectangle lr = kitLoadRect(ox, oy, i);
				chip(g, lr, "Kit " + (i + 1), filled, lr.contains(mouse));
				final Rectangle sr = kitSaveRect(ox, oy, i);
				chip(g, sr, "S", false, sr.contains(mouse));
				if (lr.contains(mouse)) hover = filled ? "Load your saved kit " + (i + 1) : "Empty — press S to save the current setup here";
				if (sr.contains(mouse)) hover = "Save the current setup to kit slot " + (i + 1);
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
			training ? "ARMOURY — CLICK TO ADD" : lms ? "KIT OPTIONS — ONE PER TAB" : "YOUR BANK — CLICK TO ADD",
			ox + PAL_X + 2, oy + LABEL_Y, LofTheme.GOLD_DIM);

		// worn gear paper-doll (varps 4641..4651)
		for (int i = 0; i < EQUIP_SLOTS; i++)
		{
			final int packed = client.getVarpValue(SLOT_VARP_BASE + i);
			final Rectangle rc = dollRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			itemSlot(g, rc, packed, SLOTS[i], hov && !lms);
			if (hov && !lms && (packed & 0xFFFF) != 0) hover = "Remove " + itemName(packed & 0xFFFF);
		}

		// inventory grid (varps 4652..4679)
		for (int i = 0; i < INV_SIZE; i++)
		{
			final int packed = client.getVarpValue(SLOT_VARP_BASE + EQUIP_SLOTS + i);
			final Rectangle rc = invRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			itemSlot(g, rc, packed, null, hov && !lms);
			if (hov && !lms && (packed & 0xFFFF) != 0) hover = "Remove " + itemName(packed & 0xFFFF);
		}

		// palette: armoury tabs (training), category tabs (LMS), or the bank with paging (bank mode)
		final int[] pal = buildPalette();
		palCache = pal;
		if (training || lms)
		{
			final String[] tabs = lms ? LMS_TABS : TABS;
			g.setFont(FontManager.getRunescapeSmallFont());
			for (int i = 0; i < tabs.length; i++)
			{
				final Rectangle rc = tabRect(ox, oy, i);
				chip(g, rc, tabs[i], i == tab, rc.contains(mouse));
			}
		}
		final int lmsSelected = lms ? (control >> (10 + 2 * Math.min(tab, LMS_CHOICES.length - 1))) & 0x3 : -1;
		for (int i = 0; i < pal.length; i++)
		{
			final Rectangle rc = palRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			itemSlot(g, rc, pal[i] | (1 << 16), null, hov);
			if (lms && i == lmsSelected)
			{
				g.setColor(LofTheme.GOLD);
				g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
			}
			if (hov) hover = (lms ? "Pick " : "Add ") + itemName(pal[i]);
		}
		if (!training && !lms)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			if (pal.length == 0)
			{
				LofTheme.shadowText(g, "Open your bank to browse items.", ox + PAL_X, palTop(oy) + 14, LofTheme.TEXT_DIM);
			}
			chip(g, pagePrevRect(ox, oy), "<", false, pagePrevRect(ox, oy).contains(mouse));
			chip(g, pageNextRect(ox, oy), ">", false, pageNextRect(ox, oy).contains(mouse));
			LofTheme.shadowText(g, "Page " + (bankPage + 1), ox + PAL_X + 78, pagePrevRect(ox, oy).y + 13, LofTheme.TEXT_DIM);
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
			LofTheme.shadowText(g, "BOT", ox + PAD + 258, oy + WIN_H - FOOT_H + 14, LofTheme.GOLD_DIM);
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

		// hover hint in the title bar (OSRS-style)
		if (hover != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, hover, titleX + 96, oy + 24, LofTheme.GOLD);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** One kit slot: the REAL item sprite (with its stack count) or the slot's ghost label. */
	private void itemSlot(Graphics2D g, Rectangle rc, int packed, String emptyLabel, boolean hov)
	{
		final int id = packed & 0xFFFF;
		final int qty = (packed >> 16) & 0xFFFF;
		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		g.setColor(LofTheme.alpha(LofTheme.EMBER, hov ? 130 : 40));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
		if (id > 0)
		{
			final BufferedImage img = itemManager.getImage(id, qty, qty > 1);
			if (img != null)
			{
				g.drawImage(img, rc.x + (rc.width - 32) / 2, rc.y + (rc.height - 32) / 2, null);
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

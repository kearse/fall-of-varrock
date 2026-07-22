/*
 * Fall of Varrock — Grand Exchange offer window (renderer + hit-testing).
 *
 * Draws the 8-slot offer board (2×4) and the offer-setup screen, styled with the shared LofTheme/
 * LofModal so it matches the shop, stake and spoils windows (always the same 480×324 frame). Geometry
 * lives here so the mouse handler agrees. Like the other lof windows it tracks a plain {@code visible}
 * flag (never reads widgets), so there's no client-thread hazard in hit-testing.
 *
 * The setup screen renders both states of a {@link LofGePlugin.Setup}: "awaiting" (item not chosen —
 * every field blank/greyed, a hint under the header) and "ready" (item + live market panel, editable
 * quantity/price). Same layout either way, so the window never resizes or jumps.
 */
package net.runelite.client.plugins.lofge;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofGeOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int COLLECT_ALL = 2;
	static final int SLOT_BASE = 100; // + box  → occupied card body → collect
	static final int ABORT_BASE = 200; // + box → abort an active offer
	static final int SLOT_BUY_BASE = 300; // + box → new BUY offer (empty card)
	static final int SLOT_SELL_BASE = 400; // + box → new SELL offer (empty card)

	// setup-view hit codes
	static final int SET_QMINUS = 11;
	static final int SET_QPLUS = 12;
	static final int SET_PMINUS = 14; // -5%
	static final int SET_PPLUS = 15;  // +5%
	static final int SET_PGUIDE = 16;
	static final int SET_PCUSTOM = 17;
	static final int SET_CONFIRM = 18;
	static final int SET_BACK = 19;
	static final int SET_QPRESET_BASE = 40; // + i (0..3 = 1/10/100/1000, 4 = custom)
	static final int ASK_ROW_BASE = 500;    // + i → adopt this Selling listing's price
	static final int BID_ROW_BASE = 520;    // + i → adopt this Buying listing's price

	private static final int PAD = LofModal.PAD;        // 14
	private static final int GRID_TOP = 46;             // below the 38px title + 8
	private static final int COL_GAP = 8;
	private static final int ROW_GAP = 8;
	private static final int COLS = 4;
	private static final int ROWS = 2;
	private static final int COL_W = (LofModal.W - PAD * 2 - COL_GAP * (COLS - 1)) / COLS; // 107
	private static final int ROW_H = 94;
	private static final int COLL_Y = GRID_TOP + ROWS * ROW_H + ROWS * ROW_GAP;            // 250
	private static final int BTN_W = 150;

	private final Client client;
	private final LofGePlugin plugin;
	private final ItemManager itemManager;

	private boolean visible;

	@Inject
	private LofGeOverlay(Client client, LofGePlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	boolean isVisible()
	{
		return visible;
	}

	void setVisible(boolean v)
	{
		visible = v;
	}

	private int originX()
	{
		return LofModal.originX(client);
	}

	private int originY()
	{
		return LofModal.originY(client);
	}

	private Rectangle slotRect(int ox, int oy, int i)
	{
		final int col = i % COLS, row = i / COLS;
		final int x = ox + PAD + col * (COL_W + COL_GAP);
		final int y = oy + GRID_TOP + row * (ROW_H + ROW_GAP);
		return new Rectangle(x, y, COL_W, ROW_H);
	}

	private Rectangle abortRect(int ox, int oy, int i)
	{
		final Rectangle s = slotRect(ox, oy, i);
		return new Rectangle(s.x + s.width - 16, s.y + 3, 13, 13);
	}

	private Rectangle collectAllRect(int ox, int oy)
	{
		return LofModal.footerButton(ox, oy, BTN_W);
	}

	// ---- setup-view geometry (fixed for both the awaiting + ready states) -----------------------
	private static final int SU_IMG_Y = 44;
	private static final int SU_NAME_Y = 57;
	private static final int SU_SUB_Y = 74;
	private static final int SU_QLAB_Y = 92;
	private static final int SU_QROW_Y = 97;
	private static final int SU_ROW_H = 22;
	private static final int SU_QPRESET_Y = 123;
	private static final int SU_PRESET_H = 19;
	private static final int SU_PLAB_Y = 150;
	private static final int SU_PROW_Y = 155;
	private static final int STEP_W = 32;
	// market panel
	private static final int MK_PANEL_TOP = 184;
	private static final int MK_SUM_Y = 196;    // summary text baseline
	private static final int MK_COLHEAD_Y = 209; // column header baseline
	private static final int MK_ROW0_Y = 213;   // first listing row top
	private static final int MK_ROW_H = 12;
	private static final int MK_ROWS = 3;
	private static final int MK_PANEL_BOT = 251;
	private static final int SU_TOTAL_Y = 266;

	private Rectangle suQMinus(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + SU_QROW_Y, STEP_W, SU_ROW_H);
	}

	private Rectangle suQPlus(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.W - PAD - STEP_W, oy + SU_QROW_Y, STEP_W, SU_ROW_H);
	}

	private Rectangle suQValue(int ox, int oy)
	{
		return new Rectangle(ox + PAD + STEP_W + 4, oy + SU_QROW_Y, LofModal.W - 2 * PAD - 2 * (STEP_W + 4), SU_ROW_H);
	}

	private Rectangle suQPreset(int ox, int oy, int i)
	{
		final int w = (LofModal.W - 2 * PAD - 4 * 6) / 5;
		return new Rectangle(ox + PAD + i * (w + 6), oy + SU_QPRESET_Y, w, SU_PRESET_H);
	}

	// price row: -5%(50) value(242) +5%(50) guide(52) custom(34)
	private Rectangle suPMinus(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + SU_PROW_Y, 50, SU_ROW_H);
	}

	private Rectangle suPValue(int ox, int oy)
	{
		return new Rectangle(ox + PAD + 56, oy + SU_PROW_Y, 242, SU_ROW_H);
	}

	private Rectangle suPPlus(int ox, int oy)
	{
		return new Rectangle(ox + PAD + 304, oy + SU_PROW_Y, 50, SU_ROW_H);
	}

	private Rectangle suPGuide(int ox, int oy)
	{
		return new Rectangle(ox + PAD + 360, oy + SU_PROW_Y, 52, SU_ROW_H);
	}

	private Rectangle suPCustom(int ox, int oy)
	{
		return new Rectangle(ox + PAD + 418, oy + SU_PROW_Y, 34, SU_ROW_H);
	}

	private int mkColW()
	{
		return (LofModal.W - 2 * PAD) / 2;
	}

	private Rectangle mkAskRow(int ox, int oy, int i)
	{
		return new Rectangle(ox + PAD, oy + MK_ROW0_Y + i * MK_ROW_H, mkColW(), MK_ROW_H);
	}

	private Rectangle mkBidRow(int ox, int oy, int i)
	{
		return new Rectangle(ox + PAD + mkColW(), oy + MK_ROW0_Y + i * MK_ROW_H, mkColW(), MK_ROW_H);
	}

	private Rectangle suBack(int ox, int oy)
	{
		return LofModal.footerChip(ox, oy, LofModal.H, 0, 120, 0);
	}

	private Rectangle suConfirm(int ox, int oy)
	{
		return LofModal.footerButton(ox, oy, 200);
	}

	private int hitTestSetup(int ox, int oy, Point p)
	{
		final LofGePlugin.Setup s = plugin.getSetup();
		if (s == null)
		{
			return INSIDE;
		}
		// Back is always live (bail out of the offer); everything else needs an item chosen.
		if (suBack(ox, oy).contains(p))
		{
			return SET_BACK;
		}
		if (s.awaitingItem())
		{
			return INSIDE;
		}
		if (suQMinus(ox, oy).contains(p))
		{
			return SET_QMINUS;
		}
		if (suQPlus(ox, oy).contains(p))
		{
			return SET_QPLUS;
		}
		for (int i = 0; i < 5; i++)
		{
			if (suQPreset(ox, oy, i).contains(p))
			{
				return SET_QPRESET_BASE + i;
			}
		}
		if (suPMinus(ox, oy).contains(p))
		{
			return SET_PMINUS;
		}
		if (suPPlus(ox, oy).contains(p))
		{
			return SET_PPLUS;
		}
		if (suPGuide(ox, oy).contains(p))
		{
			return SET_PGUIDE;
		}
		if (suPCustom(ox, oy).contains(p))
		{
			return SET_PCUSTOM;
		}
		final int nAsk = Math.min(MK_ROWS, s.asks.size());
		for (int i = 0; i < nAsk; i++)
		{
			if (mkAskRow(ox, oy, i).contains(p))
			{
				return ASK_ROW_BASE + i;
			}
		}
		final int nBid = Math.min(MK_ROWS, s.bids.size());
		for (int i = 0; i < nBid; i++)
		{
			if (mkBidRow(ox, oy, i).contains(p))
			{
				return BID_ROW_BASE + i;
			}
		}
		if (suConfirm(ox, oy).contains(p))
		{
			return SET_CONFIRM;
		}
		return INSIDE;
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, LofModal.W, LofModal.H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (plugin.getSetup() != null)
		{
			return hitTestSetup(ox, oy, p);
		}
		if (anyCollectable() && collectAllRect(ox, oy).contains(p))
		{
			return COLLECT_ALL;
		}
		final LofGePlugin.Slot[] slots = plugin.getSlots();
		for (int i = 0; i < LofGePlugin.SLOTS; i++)
		{
			final LofGePlugin.Slot s = slots[i];
			if (s == null || s.isEmpty())
			{
				// empty card: Buy / Sell buttons (OSRS-style, chosen on the card)
				if (buyBtnRect(ox, oy, i).contains(p))
				{
					return SLOT_BUY_BASE + i;
				}
				if (sellBtnRect(ox, oy, i).contains(p))
				{
					return SLOT_SELL_BASE + i;
				}
				if (slotRect(ox, oy, i).contains(p))
				{
					return INSIDE;
				}
				continue;
			}
			if (isAbortable(s) && abortRect(ox, oy, i).contains(p))
			{
				return ABORT_BASE + i;
			}
			if (slotRect(ox, oy, i).contains(p))
			{
				return SLOT_BASE + i; // occupied card body → collect
			}
		}
		return INSIDE;
	}

	private Rectangle buyBtnRect(int ox, int oy, int i)
	{
		final Rectangle r = slotRect(ox, oy, i);
		return new Rectangle(r.x + 8, r.y + 16, r.width - 16, 30);
	}

	private Rectangle sellBtnRect(int ox, int oy, int i)
	{
		final Rectangle r = slotRect(ox, oy, i);
		return new Rectangle(r.x + 8, r.y + 52, r.width - 16, 30);
	}

	private boolean isAbortable(LofGePlugin.Slot s)
	{
		return s.state == 3 || s.state == 5; // BUYING / SELLING (in progress)
	}

	private boolean anyCollectable()
	{
		for (LofGePlugin.Slot s : plugin.getSlots())
		{
			if (s != null && (s.collectCoins > 0 || s.collectItems > 0))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isEnabled() || client.getGameState() != GameState.LOGGED_IN || !visible)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same trick as the other windows).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		final LofGePlugin.Setup setup = plugin.getSetup();
		if (setup != null)
		{
			drawSetup(g, ox, oy, mouse, setup);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
			return new Dimension(LofModal.W, LofModal.H);
		}

		LofModal.frame(g, ox, oy, "Grand Exchange", LofModal.fmt(plugin.getCoins()) + " gp", mouse);

		final LofGePlugin.Slot[] slots = plugin.getSlots();
		for (int i = 0; i < LofGePlugin.SLOTS; i++)
		{
			drawSlot(g, ox, oy, i, slots[i], mouse);
		}

		drawCollection(g, ox, oy, mouse);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawSlot(Graphics2D g, int ox, int oy, int i, LofGePlugin.Slot s, Point mouse)
	{
		final Rectangle r = slotRect(ox, oy, i);
		final boolean hov = r.contains(mouse);

		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		g.setColor(LofTheme.alpha(hov ? LofTheme.EMBER : Color.BLACK, hov ? 150 : 160));
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 8, 8);

		if (s == null || s.isEmpty())
		{
			// empty card: Buy / Sell buttons, chosen here (OSRS-style) before the item search
			final Rectangle bb = buyBtnRect(ox, oy, i);
			final Rectangle sb = sellBtnRect(ox, oy, i);
			LofModal.button(g, bb, "Buy ▲", LofTheme.LAVA, true, bb.contains(mouse));
			LofModal.button(g, sb, "Sell ▼", LofTheme.GOLD, true, sb.contains(mouse));
			return;
		}

		// tag
		g.setFont(FontManager.getRunescapeSmallFont());
		final String tag = s.buy ? "BUY" : "SELL";
		LofTheme.shadowText(g, tag, r.x + 6, r.y + 14, s.buy ? LofTheme.LAVA : LofTheme.GOLD);

		// abort ✕ for in-progress offers
		if (isAbortable(s))
		{
			final Rectangle ar = abortRect(ox, oy, i);
			final boolean ah = ar.contains(mouse);
			g.setColor(ah ? LofTheme.EMBER : LofTheme.alpha(Color.WHITE, 20));
			g.fillRoundRect(ar.x, ar.y, ar.width, ar.height, 4, 4);
			final Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(1.3f));
			g.setColor(ah ? LofTheme.TEXT : LofTheme.TEXT_DIM);
			g.drawLine(ar.x + 4, ar.y + 4, ar.x + ar.width - 5, ar.y + ar.height - 5);
			g.drawLine(ar.x + ar.width - 5, ar.y + 4, ar.x + 4, ar.y + ar.height - 5);
			g.setStroke(old);
		}

		// item sprite (centred in the upper area)
		final BufferedImage img = s.itemId > 0 ? itemManager.getImage(s.itemId) : null;
		if (img != null)
		{
			g.drawImage(img, r.x + (r.width - img.getWidth()) / 2, r.y + 18, null);
		}

		// name
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String name = LofModal.fit(fm, itemName(s.itemId), r.width - 10);
		LofTheme.shadowText(g, name, r.x + (r.width - fm.stringWidth(name)) / 2, r.y + 58, LofTheme.TEXT);

		// progress bar
		final int barY = r.y + 64;
		final int barW = r.width - 12;
		g.setColor(new Color(14, 10, 8));
		g.fillRoundRect(r.x + 6, barY, barW, 7, 3, 3);
		final int frac = s.qty > 0 ? Math.min(barW, (int) ((long) barW * s.filled / s.qty)) : barW;
		g.setColor(s.buy ? LofTheme.EMBER : LofTheme.GOLD_DIM);
		g.fillRoundRect(r.x + 6, barY, frac, 7, 3, 3);

		// status line: filled/qty @ price
		final String done = (s.state == 4 || s.state == 6) ? "done" : s.filled + "/" + s.qty;
		final String line = done + " · " + LofModal.fmt(s.price) + " gp";
		LofTheme.shadowText(g, LofModal.fit(fm, line, r.width - 8), r.x + 6, r.y + 84, LofTheme.TEXT_DIM);
	}

	private void drawCollection(Graphics2D g, int ox, int oy, Point mouse)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "COLLECTION BOX", ox + PAD, oy + COLL_Y + 2, LofTheme.GOLD_DIM);

		// summarise collectable proceeds
		int stacks = 0;
		long coins = 0;
		for (LofGePlugin.Slot s : plugin.getSlots())
		{
			if (s == null)
			{
				continue;
			}
			if (s.collectItems > 0)
			{
				stacks++;
			}
			coins += s.collectCoins;
		}
		final boolean any = stacks > 0 || coins > 0;
		final String summary = any
			? (stacks + (stacks == 1 ? " stack" : " stacks") + (coins > 0 ? " · " + LofModal.fmt(coins) + " gp" : "") + " ready")
			: "Nothing to collect";
		LofModal.statusLine(g, ox, oy, summary, any ? LofTheme.TEXT : LofTheme.TEXT_DIM);

		// collect-all button (bottom-right)
		final Rectangle br = collectAllRect(ox, oy);
		LofModal.button(g, br, "Collect all", LofTheme.EMBER, any, br.contains(mouse));
	}

	private void drawSetup(Graphics2D g, int ox, int oy, Point mouse, LofGePlugin.Setup s)
	{
		final boolean ready = !s.awaitingItem();
		LofModal.frame(g, ox, oy, "Grand Exchange", s.buy ? "Buy offer" : "Sell offer", mouse);

		// item header — sprite or an empty dashed slot
		if (ready)
		{
			final BufferedImage img = itemManager.getImage(s.item);
			if (img != null)
			{
				g.drawImage(img, ox + PAD, oy + SU_IMG_Y, null);
			}
		}
		else
		{
			drawEmptyIcon(g, ox + PAD, oy + SU_IMG_Y);
		}

		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, ready ? LofModal.fit(g.getFontMetrics(), itemName(s.item), 300) : "— choose an item —",
			ox + PAD + 44, oy + SU_NAME_Y, ready ? LofTheme.TEXT : LofTheme.GOLD_DIM);

		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub;
		if (!ready)
		{
			sub = s.buy ? "Search for an item in the chat below ↓" : "Right-click an item in your inventory → Offer";
		}
		else if (s.banded())
		{
			sub = "Guide " + LofModal.fmt(s.guide) + " gp · band " + LofModal.fmt(s.floor) + "–" + LofModal.fmt(s.ceil);
		}
		else
		{
			sub = "Guide " + LofModal.fmt(s.guide) + " gp · player-listed" + (s.buy ? "" : " · you have " + s.own);
		}
		LofTheme.shadowText(g, sub, ox + PAD + 44, oy + SU_SUB_Y, ready ? LofTheme.TEXT_DIM : LofTheme.GOLD);

		// quantity
		LofTheme.shadowText(g, "QUANTITY", ox + PAD, oy + SU_QLAB_Y, LofTheme.GOLD_DIM);
		LofModal.button(g, suQMinus(ox, oy), "−", LofTheme.EMBER, ready, suQMinus(ox, oy).contains(mouse));
		drawValueBox(g, suQValue(ox, oy), ready ? LofModal.fmt(s.qty) : "—", ready);
		LofModal.button(g, suQPlus(ox, oy), "+", LofTheme.EMBER, ready, suQPlus(ox, oy).contains(mouse));
		final String[] presets = {"1", "10", "100", "1k", "X"};
		for (int i = 0; i < 5; i++)
		{
			final Rectangle pr = suQPreset(ox, oy, i);
			LofModal.button(g, pr, presets[i], LofTheme.GOLD_DIM, ready, pr.contains(mouse));
		}

		// price
		LofTheme.shadowText(g, "PRICE PER ITEM", ox + PAD, oy + SU_PLAB_Y, LofTheme.GOLD_DIM);
		if (ready)
		{
			final String hint = s.buy ? "lowest sell" : "highest buy";
			g.setFont(FontManager.getRunescapeSmallFont());
			final int hw = g.getFontMetrics().stringWidth(hint);
			LofTheme.shadowText(g, hint, ox + LofModal.W - PAD - hw, oy + SU_PLAB_Y, s.buy ? LofTheme.LAVA : LofTheme.GOLD);
		}
		LofModal.button(g, suPMinus(ox, oy), "-5%", LofTheme.EMBER, ready, suPMinus(ox, oy).contains(mouse));
		drawValueBox(g, suPValue(ox, oy), ready ? LofModal.fmt(s.price) + " gp" : "—", ready);
		LofModal.button(g, suPPlus(ox, oy), "+5%", LofTheme.EMBER, ready, suPPlus(ox, oy).contains(mouse));
		LofModal.button(g, suPGuide(ox, oy), "guide", LofTheme.GOLD_DIM, ready, suPGuide(ox, oy).contains(mouse));
		LofModal.button(g, suPCustom(ox, oy), "X", LofTheme.GOLD_DIM, ready, suPCustom(ox, oy).contains(mouse));

		// market panel
		drawMarket(g, ox, oy, mouse, s, ready);

		// total
		g.setFont(FontManager.getRunescapeFont());
		final String total = ready
			? ((s.buy ? "Total  " : "You receive  ") + LofModal.fmt(s.total()) + " gp")
			: (s.buy ? "Total  —" : "You receive  —");
		LofTheme.shadowText(g, total, ox + PAD, oy + SU_TOTAL_Y, ready ? LofTheme.TEXT : LofTheme.GOLD_DIM);

		// footer
		LofModal.button(g, suBack(ox, oy), "‹ Back", LofTheme.GOLD_DIM, true, suBack(ox, oy).contains(mouse));
		LofModal.button(g, suConfirm(ox, oy), s.buy ? "Confirm buy offer" : "Confirm sell offer",
			s.buy ? LofTheme.EMBER : LofTheme.GOLD, ready, suConfirm(ox, oy).contains(mouse));
	}

	/** The Selling / Buying two-column market snapshot (or a blank panel while awaiting an item). */
	private void drawMarket(Graphics2D g, int ox, int oy, Point mouse, LofGePlugin.Setup s, boolean ready)
	{
		final int x = ox + PAD;
		final int w = LofModal.W - 2 * PAD;
		final int top = oy + MK_PANEL_TOP;
		final int h = MK_PANEL_BOT - MK_PANEL_TOP;

		// panel background
		g.setColor(new Color(14, 10, 8));
		g.fillRoundRect(x, top, w, h, 6, 6);
		g.setColor(LofTheme.alpha(Color.BLACK, 200));
		g.drawRoundRect(x, top, w - 1, h - 1, 6, 6);

		g.setFont(FontManager.getRunescapeSmallFont());
		if (!ready)
		{
			final String m = "Market opens once you pick an item";
			final int mw = g.getFontMetrics().stringWidth(m);
			LofTheme.shadowText(g, m, ox + (LofModal.W - mw) / 2, top + h / 2 + 4, LofTheme.GOLD_DIM);
			return;
		}

		// summary line (compact so big prices never overflow the panel)
		final String bestSell = s.bestAsk > 0 ? LofModal.compact(s.bestAsk) : "—";
		final String bestBuy = s.bestBid > 0 ? LofModal.compact(s.bestBid) : "—";
		final String summary = "Best sell " + bestSell + " · Best buy " + bestBuy
			+ " · " + s.nSell + " sell / " + s.nBuy + " buy · tap a row";
		LofTheme.shadowText(g, summary, x + 6, oy + MK_SUM_Y, LofTheme.GOLD_DIM);

		// divider under summary
		g.setColor(LofTheme.alpha(Color.BLACK, 160));
		g.drawLine(x + 4, oy + MK_SUM_Y + 4, x + w - 4, oy + MK_SUM_Y + 4);
		// column divider
		final int mid = x + mkColW();
		g.drawLine(mid, oy + MK_SUM_Y + 4, mid, top + h - 4);

		LofTheme.shadowText(g, "SELLING (buy from)", x + 6, oy + MK_COLHEAD_Y, LofTheme.alpha(new Color(122, 208, 255), 255));
		LofTheme.shadowText(g, "BUYING (sell to)", mid + 6, oy + MK_COLHEAD_Y, LofTheme.alpha(new Color(255, 159, 107), 255));

		drawMarketRows(g, ox, oy, mouse, s.asks, true, ASK_ROW_BASE, new Color(191, 230, 255));
		drawMarketRows(g, ox, oy, mouse, s.bids, false, BID_ROW_BASE, new Color(255, 201, 168));
	}

	private void drawMarketRows(Graphics2D g, int ox, int oy, Point mouse, List<int[]> rows, boolean ask, int base, Color priceCol)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		final int n = Math.min(MK_ROWS, rows.size());
		if (n == 0)
		{
			final Rectangle r = ask ? mkAskRow(ox, oy, 0) : mkBidRow(ox, oy, 0);
			LofTheme.shadowText(g, "— none —", r.x + 6, r.y + 10, LofTheme.TEXT_DIM);
			return;
		}
		for (int i = 0; i < n; i++)
		{
			final Rectangle r = ask ? mkAskRow(ox, oy, i) : mkBidRow(ox, oy, i);
			if (r.contains(mouse))
			{
				g.setColor(LofTheme.ROW_HOVER);
				g.fillRect(r.x + 2, r.y, r.width - 4, r.height);
			}
			final int[] row = rows.get(i);
			LofTheme.shadowText(g, LofModal.compact(row[0]), r.x + 6, r.y + 10, priceCol);
			final String q = "× " + LofModal.compact(row[1]);
			final int qw = g.getFontMetrics().stringWidth(q);
			LofTheme.shadowText(g, q, r.x + r.width - qw - 8, r.y + 10, LofTheme.TEXT_DIM);
		}
	}

	private void drawEmptyIcon(Graphics2D g, int x, int y)
	{
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 3f}, 0f));
		g.setColor(LofTheme.GOLD_DIM);
		g.drawRoundRect(x, y, 32, 32, 6, 6);
		g.setStroke(old);
		g.setFont(FontManager.getRunescapeBoldFont());
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, "?", x + (32 - fm.stringWidth("?")) / 2, y + 22, LofTheme.GOLD_DIM);
	}

	private void drawValueBox(Graphics2D g, Rectangle r, String text, boolean ready)
	{
		g.setColor(new Color(14, 10, 8));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(LofTheme.alpha(Color.BLACK, 200));
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 6, 6);
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + r.height / 2 + 5,
			ready ? LofTheme.TEXT : LofTheme.TEXT_DIM);
	}

	private String itemName(int id)
	{
		try
		{
			final String n = itemManager.getItemComposition(id).getName();
			return n == null || n.isEmpty() ? "Item " + id : n;
		}
		catch (Exception e)
		{
			return "Item " + id;
		}
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}


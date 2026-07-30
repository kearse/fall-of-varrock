/*
 * Fall of Varrock - Grand Exchange offer window (renderer + hit-testing).
 *
 * Draws the 8-slot offer board (2x4) and the offer-setup screen, styled with the shared LofTheme/
 * LofModal. Runs at the wide 512x334 — the fixed-mode world view, so it fills the game viewport the way
 * the native GE does — but is placed and scaled by the shared authority through LofModal.beginWindow
 * (docs/overlay-design-system.md §6A/§6A'), so it re-centres in the game viewport and grows with the
 * canvas the same way the teleport/ranks/forge windows do. Geometry lives here so the mouse handler
 * agrees. Like the other lof windows it tracks a plain {@code visible} flag (never reads widgets), so
 * there's no client-thread hazard in hit-testing.
 *
 * The setup screen renders both states of a {@link LofGePlugin.Setup}: "awaiting" (item not chosen -
 * every field blank/greyed, a hint under the header) and "ready" (item + live market panel, editable
 * quantity/price). Same layout either way, so the window never resizes or jumps. "Ready" also surfaces
 * the market brain the server streams alongside the book: the last price the item actually traded at
 * with a trend arrow, and the clerk's one-line read on the price you're about to set.
 */
package net.runelite.client.plugins.lofge;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
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
	static final int TAB_OFFERS = 5;  // tab strip: show the offer board
	static final int TAB_HISTORY = 6; // tab strip: show the History tab
	static final int SLOT_BASE = 100; // + box  -> occupied card body -> collect
	static final int ABORT_BASE = 200; // + box -> abort an active offer
	static final int SLOT_BUY_BASE = 300; // + box -> new BUY offer (empty card)
	static final int SLOT_SELL_BASE = 400; // + box -> new SELL offer (empty card)

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
	static final int ASK_ROW_BASE = 500;    // + i -> adopt this Selling listing's price
	static final int BID_ROW_BASE = 520;    // + i -> adopt this Buying listing's price

	// A big window like the native OSRS Grand Exchange — the full width of the fixed-mode world view —
	// but placed and scaled by the shared authority, so it behaves like every other framed modal
	// (docs/overlay-design-system.md §6A/§6A′). 512 is the widest a window can be and still centre
	// *beside* the inventory column in fixed mode, which the sell flow depends on (it right-clicks real
	// inventory items); the same size the kit editor uses.
	//
	// Height is the world view too, not the standard 324: the offer-setup screen carries a row the other
	// modals don't — the clerk's pricing note — and it does not compress into 324. Squeezing it there put
	// "PRICE PER ITEM" through the bottom of the quantity preset row and jammed the note against the
	// QUANTITY label. At 334 the window fills the fixed-mode world view exactly, like the native GE, and
	// still clears the chat box, so placement is unaffected; it's the same kind of "fixed layout that
	// can't compress" exception the design system already lists for recruit and the style window.
	//
	// It used to author its own width range (512..600), its own origin (hand-reserving canvas to the
	// right) and no ui-scaling at all — so it was the one window that sat and sized differently from the
	// rest, and stayed a fixed pixel size while the others grew. Drawing through LofModal.beginWindow
	// now scales it with the canvas (up to SCALE_MAX, so ~819px wide on a big screen) instead of capping
	// it at 600, which makes it larger on a real monitor than the old hand-rolled range ever went.
	private static final int WIN_W = LofModal.FIXED_VIEWPORT_W; // 512
	private static final int WIN_H = LofModal.FIXED_VIEWPORT_H; // 334 — see above; 324 can't hold the note
	private static final int PAD = LofModal.PAD;               // 14
	private static final int TAB_Y = 42;                // tab strip, just below the title underline
	private static final int TAB_H = 20;
	private static final int TAB_W = 116;
	private static final int GRID_TOP = 66;             // below the tab strip
	private static final int COL_GAP = 8;
	private static final int ROW_GAP = 8;
	private static final int COLS = 4;
	private static final int ROWS = 2;
	private static final int ROW_H = 84;
	private static final int COLL_Y = GRID_TOP + ROWS * ROW_H + ROWS * ROW_GAP;            // 250
	private static final int BTN_W = 150;
	private static final int BTN_H = 28;

	private final Client client;
	private final LofGePlugin plugin;
	private final ItemManager itemManager;

	private boolean visible;

	/** This frame's origin + ui-scale, published by {@link #render} on the client thread and read (never
	 *  re-derived) by the mouse thread in {@link #hitTest} — §6A′/§8. */
	private volatile LofModal.Placement placement;

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

	/** True while a right-click menu is open — the mouse listener stands down so menu options (e.g. the
	 *  inventory "Offer" for selling) still work even if the menu overlaps the window edge. */
	boolean isGameMenuOpen()
	{
		return client.isMenuOpen();
	}

	private int colW()
	{
		return (WIN_W - PAD * 2 - COL_GAP * (COLS - 1)) / COLS;
	}

	private Rectangle tabOffersRect(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + TAB_Y, TAB_W, TAB_H);
	}

	private Rectangle tabHistoryRect(int ox, int oy)
	{
		return new Rectangle(ox + PAD + TAB_W + 6, oy + TAB_Y, TAB_W, TAB_H);
	}

	private Rectangle slotRect(int ox, int oy, int i)
	{
		final int col = i % COLS, row = i / COLS;
		final int x = ox + PAD + col * (colW() + COL_GAP);
		final int y = oy + GRID_TOP + row * (ROW_H + ROW_GAP);
		return new Rectangle(x, y, colW(), ROW_H);
	}

	private Rectangle abortRect(int ox, int oy, int i)
	{
		final Rectangle s = slotRect(ox, oy, i);
		return new Rectangle(s.x + s.width - 16, s.y + 3, 13, 13);
	}

	private Rectangle collectAllRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - BTN_W, oy + WIN_H - PAD - BTN_H, BTN_W, BTN_H);
	}

	// ---- setup-view geometry (fixed for both the awaiting + ready states) -----------------------
	// A *_Y for text is a BASELINE — the glyphs sit ABOVE it — while a *_Y for a row/box is its TOP. So a
	// label needs its baseline a full ascender past the bottom of whatever is above it, or it cuts into
	// that element's lower border. That is exactly what went wrong when this was squeezed into 324:
	// "PRICE PER ITEM" ran through the bottom of the quantity preset row, and the clerk's note collided
	// with the QUANTITY label. Don't tune these by eyeballing a screenshot — the gaps below are sized so
	// nothing collides even if the RS fonts report an ascent as large as 15px, which is well past what
	// they actually use. Running geometry, so the next edit doesn't have to re-derive it:
	//
	//   sprite       44..76      (32px item image, at x = PAD)
	//   name         58          baseline, bold,  at x = PAD + 44 (right of the sprite)
	//   sub          72          baseline, small, at x = PAD + 44
	//   clerk note   88          baseline, small, at x = PAD + 44 — indented past the sprite, NOT under
	//                            it, so the sprite's height stops constraining this row at all
	//   QUANTITY     106         baseline, small
	//   qty row      110..132
	//   presets      136..155
	//   PRICE label  172         baseline, small
	//   price row    176..198
	//   market panel 202..269
	//   total        287         baseline, regular
	//   footer       290..320    WIN_H - PAD - 30, then PAD to the bottom edge
	private static final int SU_IMG_Y = 44;
	private static final int SU_NAME_Y = 58;
	private static final int SU_SUB_Y = 72;
	private static final int SU_ADVICE_Y = 88;  // the clerk's pricing note, aligned with the name/sub
	private static final int SU_QLAB_Y = 106;
	private static final int SU_QROW_Y = 110;
	private static final int SU_ROW_H = 22;
	private static final int SU_QPRESET_Y = 136;
	private static final int SU_PRESET_H = 19;
	private static final int SU_PLAB_Y = 172;
	private static final int SU_PROW_Y = 176;
	private static final int STEP_W = 32;
	/** Text x for the header block — clear of the item sprite at {@code PAD}. */
	private static final int SU_TEXT_X = PAD + 44;
	// market panel — internal rhythm unchanged from the original (summary +12, divider +16,
	// column heads +25, rows +29, then MK_ROWS * MK_ROW_H, +2 bottom pad = 67px tall).
	private static final int MK_PANEL_TOP = 202;
	private static final int MK_SUM_Y = 214;    // summary text baseline
	private static final int MK_COLHEAD_Y = 227; // column header baseline
	private static final int MK_ROW0_Y = 231;   // first listing row top
	private static final int MK_ROW_H = 12;
	private static final int MK_ROWS = 3;
	private static final int MK_PANEL_BOT = 269;
	private static final int SU_TOTAL_Y = 287;

	/** Trend arrow colours: climbing runs hot, sliding runs cool (the same cool blue as the SELLING
	 *  column head), so direction reads at a glance without leaning on the arrow alone. */
	private static final Color TREND_UP = LofTheme.LAVA;
	private static final Color TREND_DOWN = new Color(122, 208, 255);

	private Rectangle suQMinus(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + SU_QROW_Y, STEP_W, SU_ROW_H);
	}

	private Rectangle suQPlus(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - STEP_W, oy + SU_QROW_Y, STEP_W, SU_ROW_H);
	}

	private Rectangle suQValue(int ox, int oy)
	{
		return new Rectangle(ox + PAD + STEP_W + 4, oy + SU_QROW_Y, WIN_W - 2 * PAD - 2 * (STEP_W + 4), SU_ROW_H);
	}

	private Rectangle suQPreset(int ox, int oy, int i)
	{
		final int w = (WIN_W - 2 * PAD - 4 * 6) / 5;
		return new Rectangle(ox + PAD + i * (w + 6), oy + SU_QPRESET_Y, w, SU_PRESET_H);
	}

	// price row (WIN_W-relative so it fills a wider window): -5%(50) | value(stretch) | +5%(50) guide(52) custom(34)
	private Rectangle suPMinus(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + SU_PROW_Y, 50, SU_ROW_H);
	}

	private Rectangle suPValue(int ox, int oy)
	{
		return new Rectangle(ox + PAD + 56, oy + SU_PROW_Y, WIN_W - 2 * PAD - 210, SU_ROW_H);
	}

	private Rectangle suPPlus(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - 148, oy + SU_PROW_Y, 50, SU_ROW_H);
	}

	private Rectangle suPGuide(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - 92, oy + SU_PROW_Y, 52, SU_ROW_H);
	}

	private Rectangle suPCustom(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - 34, oy + SU_PROW_Y, 34, SU_ROW_H);
	}

	private int mkColW()
	{
		return (WIN_W - 2 * PAD) / 2;
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
		return new Rectangle(ox + PAD, oy + WIN_H - PAD - 30, 120, 30);
	}

	private Rectangle suConfirm(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - 200, oy + WIN_H - PAD - 30, 200, 30);
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

	int hitTest(Point canvas)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		// Origin and scale come from the last rendered frame; map the canvas point into the window's
		// authored space so every rect below still reads in base coordinates (§6A′).
		final LofModal.Placement place = placement;
		if (place == null)
		{
			return OUTSIDE;
		}
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy, WIN_W).contains(p))
		{
			return CLOSE;
		}
		if (plugin.getSetup() != null)
		{
			return hitTestSetup(ox, oy, p);
		}
		// Tab strip (board + history views)
		if (tabOffersRect(ox, oy).contains(p))
		{
			return TAB_OFFERS;
		}
		if (tabHistoryRect(ox, oy).contains(p))
		{
			return TAB_HISTORY;
		}
		if (plugin.isShowingHistory())
		{
			return INSIDE; // history rows are read-only
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
				return SLOT_BASE + i; // occupied card body -> collect
			}
		}
		return INSIDE;
	}

	private Rectangle buyBtnRect(int ox, int oy, int i)
	{
		final Rectangle r = slotRect(ox, oy, i);
		return new Rectangle(r.x + 8, r.y + 14, r.width - 16, 28);
	}

	private Rectangle sellBtnRect(int ox, int oy, int i)
	{
		final Rectangle r = slotRect(ox, oy, i);
		return new Rectangle(r.x + 8, r.y + 46, r.width - 16, 28);
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

		// Absolute canvas coordinates - undo any renderer translate (same trick as the other windows).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		// Shared placement + ui-scale, published for the mouse thread in the same pass that draws it.
		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;
		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());

		final LofGePlugin.Setup setup = plugin.getSetup();
		if (setup != null)
		{
			drawSetup(g, ox, oy, mouse, setup);
			LofModal.endWindow(g, place);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
			return new Dimension(WIN_W, WIN_H);
		}

		final boolean hist = plugin.isShowingHistory();
		LofModal.frame(g, ox, oy, WIN_W, WIN_H, "Grand Exchange", LofModal.fmt(plugin.getCoins()) + " gp", mouse);
		drawTabs(g, ox, oy, mouse, hist);

		if (hist)
		{
			drawHistory(g, ox, oy);
		}
		else
		{
			final LofGePlugin.Slot[] slots = plugin.getSlots();
			for (int i = 0; i < LofGePlugin.SLOTS; i++)
			{
				drawSlot(g, ox, oy, i, slots[i], mouse);
			}
			drawCollection(g, ox, oy, mouse);
		}

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawTabs(Graphics2D g, int ox, int oy, Point mouse, boolean hist)
	{
		final Rectangle offers = tabOffersRect(ox, oy);
		final Rectangle history = tabHistoryRect(ox, oy);
		LofModal.button(g, offers, "Offers", hist ? LofTheme.GOLD_DIM : LofTheme.GOLD, true,
			!hist || offers.contains(mouse));
		LofModal.button(g, history, "History", hist ? LofTheme.GOLD : LofTheme.GOLD_DIM, true,
			hist || history.contains(mouse));
	}

	private void drawHistory(Graphics2D g, int ox, int oy)
	{
		final java.util.List<LofGePlugin.Hist> rows = plugin.getHistory();
		if (rows.isEmpty())
		{
			g.setFont(FontManager.getRunescapeFont());
			final String m = "No completed trades yet.";
			final int mw = g.getFontMetrics().stringWidth(m);
			LofTheme.shadowText(g, m, ox + (WIN_W - mw) / 2, oy + 150, LofTheme.TEXT_DIM);
			return;
		}
		final int x = ox + PAD;
		final int w = WIN_W - 2 * PAD;
		int y = oy + GRID_TOP;
		final int rowH = 30;
		final int max = Math.min(rows.size(), (WIN_H - PAD - GRID_TOP) / rowH); // fits without a scrollbar
		for (int i = 0; i < max; i++)
		{
			final LofGePlugin.Hist h = rows.get(i);
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(x, y, w, rowH - 4, 6, 6);

			final BufferedImage img = h.itemId > 0 ? itemManager.getImage(h.itemId, h.qty, h.qty > 1) : null;
			if (img != null)
			{
				g.drawImage(img, x + 4, y + (rowH - 4 - img.getHeight()) / 2, null);
			}

			g.setFont(FontManager.getRunescapeFont());
			final String verb = h.buy ? "Bought" : "Sold";
			final String main = verb + " " + LofModal.fmt(h.qty) + " x " + fit(g.getFontMetrics(), itemName(h.itemId), 150);
			LofTheme.shadowText(g, main, x + 42, y + 13, h.buy ? LofTheme.LAVA : LofTheme.GOLD);

			g.setFont(FontManager.getRunescapeSmallFont());
			final long total = (long) h.qty * h.price;
			final String sub = LofModal.fmt(total) + " gp  (" + LofModal.fmt(h.price) + " ea)";
			LofTheme.shadowText(g, sub, x + 42, y + 24, LofTheme.TEXT_DIM);

			final String ago = relTime(h.time);
			final int aw = g.getFontMetrics().stringWidth(ago);
			LofTheme.shadowText(g, ago, x + w - aw - 6, y + 24, LofTheme.GOLD_DIM);
			y += rowH;
		}
	}

	/** Compact "how long ago" from an epoch-millis timestamp. */
	private static String relTime(long time)
	{
		if (time <= 0)
		{
			return "";
		}
		final long secs = Math.max(0, (System.currentTimeMillis() - time) / 1000L);
		if (secs < 60)
		{
			return "just now";
		}
		if (secs < 3600)
		{
			return (secs / 60) + "m ago";
		}
		if (secs < 86_400)
		{
			return (secs / 3600) + "h ago";
		}
		return (secs / 86_400) + "d ago";
	}

	/** A small filled triangle (the up/down markers the game font can't render). Centred vertically on
	 *  {@code cy}, left edge at {@code x}, {@code w} wide. */
	private void drawTriangle(Graphics2D g, int x, int cy, int w, boolean up, Color c)
	{
		final int h = w - 2;
		final int[] xs = {x, x + w, x + w / 2};
		final int[] ys = up ? new int[]{cy + h / 2, cy + h / 2, cy - h / 2}
			: new int[]{cy - h / 2, cy - h / 2, cy + h / 2};
		g.setColor(c);
		g.fillPolygon(xs, ys, 3);
	}

	/** The Buy/Sell card button - gradient fill + accent border + label and an up/down triangle, matching
	 *  the mockup (LofModal.button is flat + can't draw the arrow glyph). */
	private void drawCardButton(Graphics2D g, Rectangle r, String label, boolean up, Color accent, boolean hover)
	{
		g.setPaint(new GradientPaint(r.x, r.y, LofTheme.alpha(accent, hover ? 120 : 78),
			r.x, r.y + r.height, LofTheme.alpha(accent, hover ? 66 : 32)));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 7, 7);
		g.setColor(LofTheme.alpha(accent, hover ? 220 : 150)); // replaces the gradient paint
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 7, 7);
		g.setColor(LofTheme.alpha(Color.WHITE, hover ? 42 : 22));
		g.drawLine(r.x + 3, r.y + 1, r.x + r.width - 4, r.y + 1);

		g.setFont(FontManager.getRunescapeBoldFont());
		final FontMetrics fm = g.getFontMetrics();
		final int triW = 8;
		final int start = r.x + (r.width - (fm.stringWidth(label) + 7 + triW)) / 2;
		LofTheme.shadowText(g, label, start, r.y + r.height / 2 + 5, accent);
		drawTriangle(g, start + fm.stringWidth(label) + 7, r.y + r.height / 2, triW, up, accent);
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
			drawCardButton(g, bb, "Buy", true, LofTheme.LAVA, bb.contains(mouse));
			drawCardButton(g, sb, "Sell", false, LofTheme.GOLD, sb.contains(mouse));
			return;
		}

		// tag: a small triangle + BUY/SELL
		g.setFont(FontManager.getRunescapeSmallFont());
		final Color tagCol = s.buy ? LofTheme.LAVA : LofTheme.GOLD;
		drawTriangle(g, r.x + 6, r.y + 10, 7, s.buy, tagCol);
		LofTheme.shadowText(g, s.buy ? "BUY" : "SELL", r.x + 16, r.y + 14, tagCol);

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
			g.drawImage(img, r.x + (r.width - img.getWidth()) / 2, r.y + 15, null);
		}

		// name
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String name = fit(fm, itemName(s.itemId), r.width - 10);
		LofTheme.shadowText(g, name, r.x + (r.width - fm.stringWidth(name)) / 2, r.y + 52, LofTheme.TEXT);

		// progress bar
		final int barY = r.y + 58;
		final int barW = r.width - 12;
		g.setColor(new Color(14, 10, 8));
		g.fillRoundRect(r.x + 6, barY, barW, 7, 3, 3);
		final int frac = s.qty > 0 ? Math.min(barW, (int) ((long) barW * s.filled / s.qty)) : barW;
		g.setColor(s.buy ? LofTheme.EMBER : LofTheme.GOLD_DIM);
		g.fillRoundRect(r.x + 6, barY, frac, 7, 3, 3);

		// status line: "Bought 3,100/5,000" (buy) / "Sold 1/1" (sell), matching the mockup
		final boolean complete = s.state == 4 || s.state == 6;
		final String verb = s.buy ? "Bought" : "Sold";
		final String line = verb + " " + LofModal.fmt(s.filled) + "/" + LofModal.fmt(s.qty);
		LofTheme.shadowText(g, fit(fm, line, r.width - 8), r.x + 6, r.y + 78,
			complete ? LofTheme.TEXT : LofTheme.TEXT_DIM);
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
		g.setFont(FontManager.getRunescapeFont());
		LofTheme.shadowText(g, summary, ox + PAD, oy + COLL_Y + 22, any ? LofTheme.TEXT : LofTheme.TEXT_DIM);

		// collect-all button (bottom-right)
		final Rectangle br = collectAllRect(ox, oy);
		LofModal.button(g, br, "Collect all", LofTheme.GOLD, any, br.contains(mouse));
	}

	private void drawSetup(Graphics2D g, int ox, int oy, Point mouse, LofGePlugin.Setup s)
	{
		final boolean ready = !s.awaitingItem();
		LofModal.frame(g, ox, oy, WIN_W, WIN_H, "Grand Exchange", s.buy ? "Buy offer" : "Sell offer", mouse);

		// item header - sprite or an empty dashed slot
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
		LofTheme.shadowText(g, ready ? fit(g.getFontMetrics(), itemName(s.item), 300) : "- choose an item -",
			ox + SU_TEXT_X, oy + SU_NAME_Y, ready ? LofTheme.TEXT : LofTheme.GOLD_DIM);

		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub;
		if (!ready)
		{
			sub = s.buy ? "Search for an item in the chat below" : "Right-click an item in your inventory to Offer";
		}
		else if (s.banded())
		{
			sub = "Guide " + LofModal.fmt(s.guide) + " gp · band " + LofModal.fmt(s.floor) + "-" + LofModal.fmt(s.ceil);
		}
		else
		{
			sub = "Guide " + LofModal.fmt(s.guide) + " gp · player-listed" + (s.buy ? "" : " · you have " + s.own);
		}
		LofTheme.shadowText(g, sub, ox + SU_TEXT_X, oy + SU_SUB_Y, ready ? LofTheme.TEXT_DIM : LofTheme.GOLD);

		// Market memory, right-aligned opposite the guide: what the item last actually changed hands for
		// here and which way it's moving. Absent (never traded) simply doesn't draw.
		if (ready && s.last > 0)
		{
			drawLastTrade(g, ox, oy, s.last, s.trend);
		}

		// The clerk's read on the opening default price — the market brain's headline, in his voice.
		if (ready && s.advice != null && !s.advice.isEmpty())
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, fit(g.getFontMetrics(), s.advice, WIN_W - PAD - SU_TEXT_X),
				ox + SU_TEXT_X, oy + SU_ADVICE_Y, LofTheme.GOLD_DIM);
		}

		// quantity
		LofTheme.shadowText(g, "QUANTITY", ox + PAD, oy + SU_QLAB_Y, LofTheme.GOLD_DIM);
		LofModal.button(g, suQMinus(ox, oy), "-", LofTheme.GOLD_DIM, ready, suQMinus(ox, oy).contains(mouse));
		drawValueBox(g, suQValue(ox, oy), ready ? LofModal.fmt(s.qty) : "-", ready);
		LofModal.button(g, suQPlus(ox, oy), "+", LofTheme.GOLD_DIM, ready, suQPlus(ox, oy).contains(mouse));
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
			LofTheme.shadowText(g, hint, ox + WIN_W - PAD - hw, oy + SU_PLAB_Y, s.buy ? LofTheme.LAVA : LofTheme.GOLD);
		}
		LofModal.button(g, suPMinus(ox, oy), "-5%", LofTheme.GOLD_DIM, ready, suPMinus(ox, oy).contains(mouse));
		drawValueBox(g, suPValue(ox, oy), ready ? LofModal.fmt(s.price) + " gp" : "-", ready);
		LofModal.button(g, suPPlus(ox, oy), "+5%", LofTheme.GOLD_DIM, ready, suPPlus(ox, oy).contains(mouse));
		LofModal.button(g, suPGuide(ox, oy), "guide", LofTheme.GOLD_DIM, ready, suPGuide(ox, oy).contains(mouse));
		LofModal.button(g, suPCustom(ox, oy), "X", LofTheme.GOLD_DIM, ready, suPCustom(ox, oy).contains(mouse));

		// market panel
		drawMarket(g, ox, oy, mouse, s, ready);

		// total
		g.setFont(FontManager.getRunescapeFont());
		final String total = ready
			? ((s.buy ? "Total  " : "You receive  ") + LofModal.fmt(s.total()) + " gp")
			: (s.buy ? "Total  -" : "You receive  -");
		LofTheme.shadowText(g, total, ox + PAD, oy + SU_TOTAL_Y, ready ? LofTheme.TEXT : LofTheme.GOLD_DIM);

		// footer
		LofModal.button(g, suBack(ox, oy), "< Back", LofTheme.GOLD_DIM, true, suBack(ox, oy).contains(mouse));
		LofModal.button(g, suConfirm(ox, oy), s.buy ? "Confirm buy offer" : "Confirm sell offer",
			LofTheme.GOLD, ready, suConfirm(ox, oy).contains(mouse));
	}

	/**
	 * "Last 1.85M gp ▲ 2.6%" — the MarketMemory readout, right-aligned to the window's inner edge.
	 * {@code trend} is signed permille (the wire unit), shown as a percentage with the arrow; a zero
	 * trend (a single remembered trade, so no direction yet) draws the figure alone.
	 */
	private void drawLastTrade(Graphics2D g, int ox, int oy, int last, int trend)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String head = "Last " + compact(last) + " gp";
		final boolean moved = trend != 0;
		final String pct = moved ? String.format("%.1f%%", Math.abs(trend) / 10.0) : "";
		final int triW = 7;
		final int extra = moved ? 4 + triW + 3 + fm.stringWidth(pct) : 0;
		final int x = ox + WIN_W - PAD - fm.stringWidth(head) - extra;

		LofTheme.shadowText(g, head, x, oy + SU_SUB_Y, LofTheme.TEXT_DIM);
		if (moved)
		{
			final Color c = trend > 0 ? TREND_UP : TREND_DOWN;
			final int triX = x + fm.stringWidth(head) + 4;
			drawTriangle(g, triX, oy + SU_SUB_Y - 4, triW, trend > 0, c);
			LofTheme.shadowText(g, pct, triX + triW + 3, oy + SU_SUB_Y, c);
		}
	}

	/** The Selling / Buying two-column market snapshot (or a blank panel while awaiting an item). */
	private void drawMarket(Graphics2D g, int ox, int oy, Point mouse, LofGePlugin.Setup s, boolean ready)
	{
		final int x = ox + PAD;
		final int w = WIN_W - 2 * PAD;
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
			LofTheme.shadowText(g, m, ox + (WIN_W - mw) / 2, top + h / 2 + 4, LofTheme.GOLD_DIM);
			return;
		}

		// summary line (compact so big prices never overflow the panel)
		final String bestSell = s.bestAsk > 0 ? compact(s.bestAsk) : "-";
		final String bestBuy = s.bestBid > 0 ? compact(s.bestBid) : "-";
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
			LofTheme.shadowText(g, "- none -", r.x + 6, r.y + 10, LofTheme.TEXT_DIM);
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
			LofTheme.shadowText(g, compact(row[0]), r.x + 6, r.y + 10, priceCol);
			final String q = "x " + compact(row[1]);
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
		// Clip to the box so a long value never overruns into the adjacent stepper buttons.
		final String shown = fit(fm, text, r.width - 8);
		LofTheme.shadowText(g, shown, r.x + (r.width - fm.stringWidth(shown)) / 2, r.y + r.height / 2 + 5,
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

	/** Compact number for the tight market cells: 1,234 -> "1,234"; 1.85M / 12.4k for big values. */
	private static String compact(long v)
	{
		if (v >= 10_000_000)
		{
			return (v / 1_000_000) + "M";
		}
		if (v >= 1_000_000)
		{
			return String.format("%.2fM", v / 1_000_000.0);
		}
		if (v >= 100_000)
		{
			return (v / 1000) + "k";
		}
		if (v >= 10_000)
		{
			return String.format("%.1fk", v / 1000.0);
		}
		return LofModal.fmt(v);
	}

	/** Truncate text with an ellipsis to fit maxW pixels. */
	private String fit(FontMetrics fm, String text, int maxW)
	{
		if (fm.stringWidth(text) <= maxW)
		{
			return text;
		}
		final String ell = "...";
		final int ew = fm.stringWidth(ell);
		final StringBuilder sb = new StringBuilder();
		int w = 0;
		for (int i = 0; i < text.length(); i++)
		{
			final int cw = fm.charWidth(text.charAt(i));
			if (w + cw + ew > maxW)
			{
				break;
			}
			sb.append(text.charAt(i));
			w += cw;
		}
		return sb.append(ell).toString();
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}

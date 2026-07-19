/*
 * Fall of Varrock — Supply Depot window (renderer + hit-testing).
 *
 * A store you sell TO: category tabs (food / potions / bars / ores / logs / raw fish), the
 * rotating Supply Drive banner (the horn icon + the boosted category), rows for everything you
 * carry with per-row 1 / 5 / All hand-ins, an "accepted" icon grid for the rest (hover = value),
 * the realm supply meter, and Hand In All. Every item draws its real game icon via ItemManager.
 */
package net.runelite.client.plugins.lofsupply;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
import net.runelite.client.util.ImageUtil;

class LofSupplyOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int HAND_IN_TAB = 2;
	static final int HAND_IN_ALL = 3;
	static final int TAB_BASE = 100;
	static final int CHIP_BASE = 200; // + row*3 + chip (0 → 1, 1 → 5, 2 → All)

	static class Entry
	{
		int id;
		int we;
		int carried;
	}

	static class Cat
	{
		String label = "";
		final List<Entry> entries = new ArrayList<>();
	}

	private static final int TABS_Y = LofModal.TITLE_H + 8;
	private static final int TAB_H = 24;
	private static final int BANNER_Y = TABS_Y + TAB_H + 6;
	private static final int BANNER_H = 30;
	private static final int ROWS_Y = BANNER_Y + BANNER_H + 8;
	private static final int ROW_H = 26;
	private static final int ROW_STEP = 28;
	private static final int MAX_ROWS = 5;
	private static final int GRID_CELL = 26;

	private final Client client;
	private final ItemManager itemManager;

	private boolean visible;
	private final List<Cat> cats = new ArrayList<>();
	private int driveIdx = -1;
	private int mult = 2;
	private int meter;
	private int meterMax = 1;
	private long warEffort;
	private int activeTab;

	private BufferedImage horn;
	private boolean iconLoaded;

	@Inject
	private LofSupplyOverlay(Client client, ItemManager itemManager)
	{
		this.client = client;
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

	int getActiveTab()
	{
		return activeTab;
	}

	void setActiveTab(int t)
	{
		if (t >= 0 && t < cats.size())
		{
			activeTab = t;
		}
	}

	void beginManifest(int nCats, int driveIdx, int mult, int meter, int meterMax, long warEffort)
	{
		cats.clear();
		for (int i = 0; i < nCats; i++)
		{
			cats.add(new Cat());
		}
		this.driveIdx = driveIdx;
		this.mult = mult;
		this.meter = meter;
		this.meterMax = Math.max(1, meterMax);
		this.warEffort = warEffort;
		if (activeTab >= nCats)
		{
			activeTab = 0;
		}
	}

	void addCategoryChunk(int catIdx, String label, boolean first, List<Entry> entries)
	{
		if (catIdx < 0 || catIdx >= cats.size())
		{
			return;
		}
		final Cat cat = cats.get(catIdx);
		cat.label = label;
		if (first)
		{
			cat.entries.clear();
		}
		cat.entries.addAll(entries);
	}

	private Cat active()
	{
		return activeTab >= 0 && activeTab < cats.size() ? cats.get(activeTab) : null;
	}

	/** Rows shown for the active tab: carried items only, capped at [MAX_ROWS]. */
	List<Entry> carriedRows()
	{
		final Cat cat = active();
		if (cat == null)
		{
			return new ArrayList<>();
		}
		final List<Entry> out = new ArrayList<>();
		for (Entry e : cat.entries)
		{
			if (e.carried > 0)
			{
				out.add(e);
				if (out.size() >= MAX_ROWS)
				{
					break;
				}
			}
		}
		return out;
	}

	private long tabWorth()
	{
		final Cat cat = active();
		if (cat == null)
		{
			return 0;
		}
		long total = 0;
		final boolean boosted = activeTab == driveIdx;
		for (Entry e : cat.entries)
		{
			total += (long) e.carried * e.we * (boosted ? mult : 1);
		}
		return total;
	}

	private long allWorth()
	{
		long total = 0;
		for (int c = 0; c < cats.size(); c++)
		{
			final boolean boosted = c == driveIdx;
			for (Entry e : cats.get(c).entries)
			{
				total += (long) e.carried * e.we * (boosted ? mult : 1);
			}
		}
		return total;
	}

	private Rectangle tabRect(int ox, int oy, int i)
	{
		final int w = (LofModal.W - 2 * LofModal.PAD - 5 * 4) / 6;
		return new Rectangle(ox + LofModal.PAD + i * (w + 4), oy + TABS_Y, w, TAB_H);
	}

	private Rectangle rowRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD, oy + ROWS_Y + i * ROW_STEP, LofModal.W - 2 * LofModal.PAD, ROW_H);
	}

	private Rectangle chipRect(int ox, int oy, int row, int chip)
	{
		final Rectangle r = rowRect(ox, oy, row);
		return new Rectangle(r.x + r.width - 110 + chip * 37, r.y + 3, 33, 20);
	}

	private Rectangle handInTabRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.PAD, oy + LofModal.H - 12 - 32, 190, 32);
	}

	private Rectangle handInAllRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.W - LofModal.PAD - 190, oy + LofModal.H - 12 - 32, 190, 32);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		if (!new Rectangle(ox, oy, LofModal.W, LofModal.H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		for (int i = 0; i < cats.size(); i++)
		{
			if (tabRect(ox, oy, i).contains(p))
			{
				return TAB_BASE + i;
			}
		}
		final int rows = carriedRows().size();
		for (int r = 0; r < rows; r++)
		{
			for (int c = 0; c < 3; c++)
			{
				if (chipRect(ox, oy, r, c).contains(p))
				{
					return CHIP_BASE + r * 3 + c;
				}
			}
		}
		if (tabWorth() > 0 && handInTabRect(ox, oy).contains(p))
		{
			return HAND_IN_TAB;
		}
		if (allWorth() > 0 && handInAllRect(ox, oy).contains(p))
		{
			return HAND_IN_ALL;
		}
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);
		if (!iconLoaded)
		{
			iconLoaded = true;
			try
			{
				horn = ImageUtil.loadImageResource(LofSupplyOverlay.class, "drive_horn.png");
			}
			catch (Exception e)
			{
				horn = null;
			}
		}

		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		final Point mouse = mousePoint();
		LofModal.frame(g, ox, oy, "Supply Depot", "Quartermaster · War Effort: " + LofModal.fmt(warEffort), mouse);

		// category tabs
		g.setFont(FontManager.getRunescapeSmallFont());
		for (int i = 0; i < cats.size(); i++)
		{
			final Rectangle tr = tabRect(ox, oy, i);
			final boolean sel = i == activeTab;
			final boolean hov = tr.contains(mouse);
			g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 44) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
			if (sel)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
				g.drawRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
			}
			// the drive tab is marked by colour (LAVA) — the RuneScape fonts have no emoji glyphs
			final String label = cats.get(i).label;
			final FontMetrics fm = g.getFontMetrics();
			LofTheme.shadowText(g, label, tr.x + (tr.width - fm.stringWidth(label)) / 2, tr.y + 16,
				sel ? LofTheme.GOLD : i == driveIdx ? LofTheme.LAVA : LofTheme.TEXT_DIM);
		}

		// supply-drive banner
		if (driveIdx >= 0 && driveIdx < cats.size())
		{
			final Rectangle br = new Rectangle(ox + LofModal.PAD, oy + BANNER_Y, LofModal.W - 2 * LofModal.PAD, BANNER_H);
			g.setColor(LofTheme.alpha(LofTheme.LAVA, 30));
			g.fillRoundRect(br.x, br.y, br.width, br.height, 10, 10);
			g.setColor(LofTheme.alpha(LofTheme.LAVA, 140));
			g.drawRoundRect(br.x, br.y, br.width, br.height, 10, 10);
			if (horn != null)
			{
				g.drawImage(horn, br.x + 6, br.y + 3, 24, 24, null);
			}
			LofTheme.shadowText(g, "SUPPLY DRIVE — the front is short on " + cats.get(driveIdx).label.toLowerCase()
				+ ": " + mult + "x War Effort", br.x + 38, br.y + 19, LofTheme.LAVA);
		}

		// carried rows
		final List<Entry> rows = carriedRows();
		g.setFont(FontManager.getRunescapeSmallFont());
		int gridY = oy + ROWS_Y;
		if (rows.isEmpty())
		{
			LofTheme.shadowText(g, "You carry nothing in this category.", ox + LofModal.PAD + 4, oy + ROWS_Y + 16, LofTheme.TEXT_DIM);
			gridY += 28;
		}
		else
		{
			final boolean boosted = activeTab == driveIdx;
			for (int i = 0; i < rows.size(); i++)
			{
				final Entry e = rows.get(i);
				final Rectangle rr = rowRect(ox, oy, i);
				g.setColor(rr.contains(mouse) ? LofTheme.ROW_HOVER : LofTheme.ROW);
				g.fillRoundRect(rr.x, rr.y, rr.width, rr.height, 8, 8);
				drawItem(g, rr.x + 4, rr.y + 2, e.id, 26, 23);
				final int weEach = e.we * (boosted ? mult : 1);
				LofTheme.shadowText(g, itemName(e.id) + "  x" + e.carried, rr.x + 36, rr.y + 17, LofTheme.TEXT);
				final String worth = weEach + " WE each";
				LofTheme.shadowText(g, worth, rr.x + 230, rr.y + 17, boosted ? LofTheme.LAVA : LofTheme.GOLD);
				for (int c = 0; c < 3; c++)
				{
					final Rectangle cr = chipRect(ox, oy, i, c);
					final boolean hov = cr.contains(mouse);
					g.setColor(hov ? LofTheme.alpha(LofTheme.GOLD, 40) : LofTheme.alpha(LofTheme.GOLD, 14));
					g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 10, 10);
					g.setColor(LofTheme.alpha(LofTheme.GOLD, 90));
					g.drawRoundRect(cr.x, cr.y, cr.width, cr.height, 10, 10);
					final String cl = c == 0 ? "1" : c == 1 ? "5" : "All";
					final FontMetrics fm = g.getFontMetrics();
					LofTheme.shadowText(g, cl, cr.x + (cr.width - fm.stringWidth(cl)) / 2, cr.y + 14, LofTheme.GOLD);
				}
			}
			gridY += rows.size() * ROW_STEP + 4;
			final Cat cat = active();
			int carriedKinds = 0;
			if (cat != null)
			{
				for (Entry e : cat.entries)
				{
					if (e.carried > 0)
					{
						carriedKinds++;
					}
				}
			}
			if (carriedKinds > rows.size())
			{
				LofTheme.shadowText(g, "+" + (carriedKinds - rows.size()) + " more kinds — Hand in category takes everything.",
					ox + LofModal.PAD + 4, gridY + 10, LofTheme.TEXT_DIM);
				gridY += 18;
			}
		}

		// accepted grid (everything in the category; hover shows name + value)
		LofTheme.shadowText(g, "ACCEPTED — hover for value", ox + LofModal.PAD + 2, gridY + 12, LofTheme.GOLD_DIM);
		gridY += 18;
		final Cat cat = active();
		Entry hovered = null;
		int hx = 0, hy = 0;
		if (cat != null)
		{
			final int perRow = (LofModal.W - 2 * LofModal.PAD) / GRID_CELL;
			final int maxRows = Math.max(1, (oy + LofModal.H - 96 - gridY) / GRID_CELL);
			for (int i = 0; i < cat.entries.size() && i < perRow * maxRows; i++)
			{
				final Entry e = cat.entries.get(i);
				final int cx = ox + LofModal.PAD + (i % perRow) * GRID_CELL;
				final int cy = gridY + (i / perRow) * GRID_CELL;
				final Rectangle cell = new Rectangle(cx, cy, GRID_CELL, GRID_CELL);
				if (cell.contains(mouse))
				{
					hovered = e;
					hx = cx;
					hy = cy;
					g.setColor(LofTheme.ROW_HOVER);
					g.fillRoundRect(cx, cy, GRID_CELL, GRID_CELL, 6, 6);
				}
				drawItem(g, cx + 1, cy + 2, e.id, 24, 21);
			}
		}

		// realm supply meter
		final int meterY = oy + LofModal.H - 76;
		LofTheme.shadowText(g, "REALM WAR SUPPLY", ox + LofModal.PAD, meterY - 4, LofTheme.GOLD_DIM);
		final String mv = LofModal.fmt(meter) + " / " + LofModal.fmt(meterMax);
		LofTheme.shadowText(g, mv, ox + LofModal.W - LofModal.PAD - g.getFontMetrics().stringWidth(mv), meterY - 4, LofTheme.GOLD);
		g.setColor(new Color(12, 10, 9));
		g.fillRoundRect(ox + LofModal.PAD, meterY, LofModal.W - 2 * LofModal.PAD, 10, 5, 5);
		g.setColor(LofTheme.EMBER_DARK);
		g.drawRoundRect(ox + LofModal.PAD, meterY, LofModal.W - 2 * LofModal.PAD, 10, 5, 5);
		final int fillW = (int) ((long) (LofModal.W - 2 * LofModal.PAD) * Math.min(meter, meterMax) / meterMax);
		if (fillW > 2)
		{
			g.setColor(LofTheme.EMBER);
			g.fillRoundRect(ox + LofModal.PAD, meterY, fillW, 10, 5, 5);
		}

		// footer buttons
		final long tabWorth = tabWorth();
		final long allWorth = allWorth();
		final String tabLabel = cat != null ? "Hand in " + cat.label.toLowerCase() + " — " + LofModal.fmt(tabWorth) + " WE" : "Hand in";
		LofModal.button(g, handInTabRect(ox, oy), tabLabel, LofTheme.GOLD, tabWorth > 0, handInTabRect(ox, oy).contains(mouse));
		LofModal.button(g, handInAllRect(ox, oy), "Hand In All — " + LofModal.fmt(allWorth) + " WE", LofTheme.EMBER, allWorth > 0, handInAllRect(ox, oy).contains(mouse));

		// hover tooltip last, above everything
		if (hovered != null)
		{
			final boolean boosted = activeTab == driveIdx;
			final String tip = itemName(hovered.id) + " — " + (hovered.we * (boosted ? mult : 1)) + " WE";
			final FontMetrics fm = g.getFontMetrics();
			final int tw = fm.stringWidth(tip) + 14;
			int tx = Math.max(ox + 6, Math.min(hx - tw / 2, ox + LofModal.W - tw - 6));
			g.setColor(new Color(12, 10, 9, 245));
			g.fillRoundRect(tx, hy - 24, tw, 20, 8, 8);
			g.setColor(LofTheme.GOLD_DIM);
			g.drawRoundRect(tx, hy - 24, tw, 20, 8, 8);
			LofTheme.shadowText(g, tip, tx + 7, hy - 10, LofTheme.TEXT);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawItem(Graphics2D g, int x, int y, int itemId, int w, int h)
	{
		final BufferedImage img = itemManager.getImage(itemId);
		if (img != null)
		{
			g.drawImage(img, x, y, w, h, null);
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			return client.getItemDefinition(itemId).getName();
		}
		catch (Exception e)
		{
			return "item " + itemId;
		}
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}

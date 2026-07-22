/*
 * Fall of Varrock — War Forge window (renderer + hit-testing).
 *
 * Style tabs (Melee / Ranged / Magic), recipe rows drawn with the REAL base → result item
 * sprites (Bandos chestplate ➜ Torva platebody, ...), and a live material checklist —
 * Commendations, runite bars, coins, Warden's embers, all with their real item icons.
 * FORGE IT lights only when every line is green; the server re-validates regardless.
 */
package net.runelite.client.plugins.lofforge;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofForgeOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int FORGE = 2;
	static final int TAB_BASE = 50;
	static final int ROW_BASE = 100;

	static final String[] STYLES = {"Melee", "Ranged", "Magic"};

	static class Recipe
	{
		int index;
		String style = "";
		int baseId;
		int outId;
		int comm;
		int bars;
		int coins;
		int embers;
		int baseHave;
	}

	private static final int TABS_Y = LofModal.TITLE_H + 8;
	private static final int TAB_H = 24;
	private static final int ROWS_Y = TABS_Y + TAB_H + 8;
	private static final int ROW_H = 34;
	private static final int ROW_STEP = 38;
	private static final int CHECK_H = 92;
	// The recipe list scrolls inside a clipped viewport; the material checklist is pinned at a fixed
	// spot above the footer (not floated under the rows), so the window fits the short standard height.
	// Anchored off the shared status baseline, so the checklist, the status line and the FORGE IT
	// button stack without touching — the confirm warning used to be drawn across the button itself.
	private static final int LIST_TOP = ROWS_Y;                                  // 78
	private static final int CHECK_Y = LofModal.statusY(LofModal.H) - 12 - CHECK_H; // above the status line
	private static final int LIST_BOTTOM = CHECK_Y - 6;
	private static final int LIST_H = LIST_BOTTOM - LIST_TOP;

	private final Client client;
	private final ItemManager itemManager;

	private boolean visible;
	private List<Recipe> recipes = new ArrayList<>();
	private int commId = -1, emberId = -1, barId = -1, coinId = -1;
	private int commHave, embersHave, barsHave, coinsHave;
	private int activeTab;
	private int selected;
	private int scroll; // px — recipe-list scroll offset

	/** Two-click confirm: forging consumes a BIS base — the first click arms, the second sends. */
	private boolean armed;

	@Inject
	private LofForgeOverlay(Client client, ItemManager itemManager)
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
		armed = false;
	}

	boolean isArmed()
	{
		return armed;
	}

	void setArmed(boolean v)
	{
		armed = v;
	}

	void setCurrencies(int commId, int emberId, int barId, int coinId,
		int commHave, int embersHave, int barsHave, int coinsHave)
	{
		this.commId = commId;
		this.emberId = emberId;
		this.barId = barId;
		this.coinId = coinId;
		this.commHave = commHave;
		this.embersHave = embersHave;
		this.barsHave = barsHave;
		this.coinsHave = coinsHave;
	}

	void setRecipes(List<Recipe> rows)
	{
		recipes = rows;
		selected = 0;
		scroll = 0;
		// A re-push mid-confirm must disarm: selection resets to row 0, and an armed second
		// click would otherwise forge the WRONG recipe — the exact mis-commit the confirm guards.
		armed = false;
	}

	void setActiveTab(int t)
	{
		if (t >= 0 && t < STYLES.length)
		{
			activeTab = t;
			selected = 0;
			scroll = 0;
			armed = false;
		}
	}

	/** Recipes for the active style tab, in pushed order. */
	List<Recipe> tabRecipes()
	{
		final List<Recipe> out = new ArrayList<>();
		for (Recipe r : recipes)
		{
			if (STYLES[activeTab].equals(r.style))
			{
				out.add(r);
			}
		}
		return out;
	}

	Recipe selectedRecipe()
	{
		final List<Recipe> tab = tabRecipes();
		return selected >= 0 && selected < tab.size() ? tab.get(selected) : null;
	}

	void setSelected(int i)
	{
		if (i >= 0 && i < tabRecipes().size())
		{
			selected = i;
			armed = false;
		}
	}

	boolean satisfied(Recipe r)
	{
		return r != null && r.baseHave > 0 && commHave >= r.comm && barsHave >= r.bars
			&& coinsHave >= r.coins && (r.embers <= 0 || embersHave >= r.embers);
	}

	private Rectangle tabRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD + i * 92, oy + TABS_Y, 86, TAB_H);
	}

	private Rectangle rowRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD, oy + LIST_TOP + i * ROW_STEP - scroll, LofModal.W - 2 * LofModal.PAD, ROW_H);
	}

	private Rectangle listRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.PAD, oy + LIST_TOP, LofModal.W - 2 * LofModal.PAD, LIST_H);
	}

	boolean handleScroll(Point p, int rotation)
	{
		if (!visible)
		{
			return false;
		}
		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		if (!listRect(ox, oy).contains(p))
		{
			return false;
		}
		scroll = LofModal.clampScroll(scroll + rotation * ROW_STEP, tabRecipes().size() * ROW_STEP, LIST_H);
		return true;
	}

	private Rectangle forgeRect(int ox, int oy)
	{
		return LofModal.footerButton(ox, oy, 170);
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
		for (int i = 0; i < STYLES.length; i++)
		{
			if (tabRect(ox, oy, i).contains(p))
			{
				return TAB_BASE + i;
			}
		}
		// Rows gated to the clipped list viewport so a scrolled-out row can't take a click.
		if (listRect(ox, oy).contains(p))
		{
			final int rel = p.y - (oy + LIST_TOP) + scroll;
			final int i = rel / ROW_STEP;
			if (i >= 0 && i < tabRecipes().size() && (rel - i * ROW_STEP) <= ROW_H)
			{
				return ROW_BASE + i;
			}
		}
		if (satisfied(selectedRecipe()) && forgeRect(ox, oy).contains(p))
		{
			return FORGE;
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

		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		final Point mouse = mousePoint();
		LofModal.frame(g, ox, oy, "The War Forge", "Royal Smith · Knight+", mouse);

		// style tabs
		g.setFont(FontManager.getRunescapeSmallFont());
		for (int i = 0; i < STYLES.length; i++)
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
			final FontMetrics fm = g.getFontMetrics();
			LofTheme.shadowText(g, STYLES[i], tr.x + (tr.width - fm.stringWidth(STYLES[i])) / 2, tr.y + 16,
				sel ? LofTheme.GOLD : LofTheme.TEXT_DIM);
		}

		// recipe rows: base sprite ➜ result sprite, cost summary on the right (scrolls in a clip)
		final List<Recipe> tab = tabRecipes();
		final int contentH = tab.size() * ROW_STEP;
		scroll = LofModal.clampScroll(scroll, contentH, LIST_H);
		final Shape listClip = g.getClip();
		g.setClip(ox + LofModal.PAD, oy + LIST_TOP, LofModal.W - 2 * LofModal.PAD, LIST_H);
		for (int i = 0; i < tab.size(); i++)
		{
			final Recipe r = tab.get(i);
			final Rectangle rr = rowRect(ox, oy, i);
			if (rr.y + ROW_H < oy + LIST_TOP || rr.y > oy + LIST_TOP + LIST_H)
			{
				continue; // scrolled out of the viewport
			}
			final boolean sel = i == selected;
			final boolean hov = rr.contains(mouse);
			g.setColor(sel ? LofTheme.alpha(LofTheme.GOLD, 20) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rr.x, rr.y, rr.width, rr.height, 8, 8);
			if (sel)
			{
				g.setColor(LofTheme.alpha(LofTheme.GOLD, 130));
				g.drawRoundRect(rr.x, rr.y, rr.width, rr.height, 8, 8);
			}
			drawItem(g, rr.x + 6, rr.y + 3, r.baseId, 30, 27);
			g.setFont(FontManager.getRunescapeFont());
			LofTheme.shadowText(g, "->", rr.x + 42, rr.y + 22, LofTheme.EMBER);
			drawItem(g, rr.x + 62, rr.y + 3, r.outId, 30, 27);
			// Result name left, cost summary right — the name is fitted against the cost so a long
			// item can never run into it.
			g.setFont(FontManager.getRunescapeSmallFont());
			final String cost = r.comm + " comm · " + r.bars + " bars · " + LofModal.fmt(r.coins)
				+ (r.embers > 0 ? " · " + r.embers + " ember" : "");
			LofModal.rowText(g, rr.x + 100, rr.y + 22, rr.width - 100 - 10, itemName(r.outId), cost,
				sel ? LofTheme.GOLD : LofTheme.TEXT, LofTheme.TEXT_DIM);
		}
		g.setClip(listClip);
		LofModal.scrollbar(g, ox + LofModal.W - 10, oy + LIST_TOP, LIST_H, contentH, scroll);

		// material checklist for the selected recipe
		final Recipe sel = selectedRecipe();
		if (sel != null)
		{
			final int cy = oy + CHECK_Y; // pinned above the footer, independent of the scrolled list
			final Rectangle cr = new Rectangle(ox + LofModal.PAD, cy, LofModal.W - 2 * LofModal.PAD, CHECK_H);
			g.setColor(new Color(0, 0, 0, 80));
			g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 10, 10);
			g.setColor(LofTheme.alpha(LofTheme.EMBER_DARK, 160));
			g.drawRoundRect(cr.x, cr.y, cr.width, cr.height, 10, 10);
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "FORGING: " + itemName(sel.outId).toUpperCase(), cr.x + 10, cr.y + 15, LofTheme.GOLD_DIM);

			int ly = cr.y + 30;
			ly = checkLine(g, cr, ly, sel.baseId, itemName(sel.baseId), sel.baseHave, 1);
			ly = checkLine(g, cr, ly, commId, "Commendations (untradeable — earned marching)", commHave, sel.comm);
			ly = checkLine(g, cr, ly, barId, "Runite bars", barsHave, sel.bars);
			ly = checkLine(g, cr, ly, coinId, "Coin fee", coinsHave, sel.coins);
			if (sel.embers > 0)
			{
				checkLine(g, cr, ly, emberId, "Warden's ember (the Grand March)", embersHave, sel.embers);
			}
		}

		LofModal.statusLine(g, ox, oy,
			armed ? "This consumes the base piece and materials — click again to commit."
				: "A success is announced to the whole realm.",
			armed ? LofTheme.LAVA : LofTheme.TEXT_DIM);
		final boolean can = satisfied(sel);
		LofModal.button(g, forgeRect(ox, oy), armed ? "CONFIRM — FORGE IT" : "FORGE IT",
			armed ? LofTheme.LAVA : LofTheme.GOLD, can, forgeRect(ox, oy).contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private int checkLine(Graphics2D g, Rectangle cr, int y, int itemId, String label, int have, int need)
	{
		drawItem(g, cr.x + 8, y - 12, itemId, 18, 16);
		final boolean ok = have >= need;
		final String status = (ok ? "OK  " : "X  ") + LofModal.fmt(have) + " / " + LofModal.fmt(need);
		LofModal.rowText(g, cr.x + 32, y, cr.width - 32 - 10, label, status, LofTheme.TEXT,
			ok ? new Color(110, 205, 110) : new Color(255, 138, 117));
		return y + 15;
	}

	private void drawItem(Graphics2D g, int x, int y, int itemId, int w, int h)
	{
		if (itemId < 0)
		{
			return;
		}
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


	@Override
	public boolean isWindowVisible()
	{
		return visible;
	}

	@Override
	public void hideWindow()
	{
		visible = false;
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}

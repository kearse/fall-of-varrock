/*
 * Fall of Varrock — the making window (renderer + hit-testing).
 *
 * Recipe rows with real item icons (result + materials), level/xp on the right, locked rows
 * dimmed below the player's level; a 1/5/10/ALL quantity picker and a MAKE button that lights
 * only when the selected recipe is makeable. One frame serves every production station — the
 * server names the window (Furnace / Anvil — Steel / ...).
 */
package net.runelite.client.plugins.lofmake;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofMakeOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int MAKE = 2;
	static final int QTY_BASE = 50;   // +0..3 → 1 / 5 / 10 / ALL
	static final int ROW_BASE = 100;

	static final int[] QTY_OPTIONS = {1, 5, 10, -1}; // -1 = ALL (max makeable)

	static class Recipe
	{
		int resultId;
		int level;
		int xp10;
		final List<int[]> mats = new ArrayList<>(); // {itemId, qty}
	}

	private static final int ROWS_Y = LofModal.TITLE_H + 10;
	private static final int ROW_H = 34;
	private static final int ROW_STEP = 38;
	// The recipe list scrolls inside a clipped viewport (LofModal.scrollbar), so any number of recipes
	// fits the short standard window — rows are clipped to LIST_H and never reach the status line
	// (H-56) or footer buttons (H-44), which would otherwise cover MAKE and swallow its click.
	private static final int LIST_TOP = ROWS_Y;                              // 48
	private static final int LIST_BOTTOM = LofModal.statusY(LofModal.H) - 12; // clear of the status line
	private static final int LIST_H = LIST_BOTTOM - LIST_TOP;

	private final Client client;
	private final ItemManager itemManager;

	private boolean visible;
	private String title = "Making";
	private List<Recipe> recipes = new ArrayList<>();
	private int selected;
	private int qtyChoice = 3; // default ALL
	private int scroll; // px — recipe-list scroll offset

	// Computed on the CLIENT thread during render() and read by the mouse thread. Reading the
	// inventory container (and skill levels) off the client thread returns null/stale, so the
	// gate would fail and hitTest would answer INSIDE instead of MAKE — the click was then
	// swallowed and the button looked dead even though it had rendered lit. Same fix, and same
	// reason, as LofShopTabsOverlay's cached showing/winRect. The click path must NEVER touch
	// client state directly.
	private volatile boolean canMakeCached;
	private volatile int qtyCached;
	private volatile int oxCached, oyCached;

	@Inject
	private LofMakeOverlay(Client client, ItemManager itemManager)
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

	void setRecipes(String title, List<Recipe> rows)
	{
		this.title = title;
		this.recipes = rows;
		qtyChoice = 3;
		scroll = 0;
		// Preselect a row the player can ACTUALLY make. Row 0 (bronze) used to be selected
		// blind, so someone carrying only iron opened the window on a dead MAKE button and
		// clicking it did nothing.
		selected = 0;
		for (int i = 0; i < rows.size(); i++)
		{
			if (levelOk(rows.get(i)) && maxMakeable(rows.get(i)) > 0)
			{
				selected = i;
				break;
			}
		}
	}

	void setSelected(int i)
	{
		if (i >= 0 && i < recipes.size())
		{
			selected = i;
		}
	}

	void setQtyChoice(int i)
	{
		if (i >= 0 && i < QTY_OPTIONS.length)
		{
			qtyChoice = i;
		}
	}

	Recipe selectedRecipe()
	{
		return selected >= 0 && selected < recipes.size() ? recipes.get(selected) : null;
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	int cachedQty()
	{
		return qtyCached;
	}

	/** The chosen quantity resolved against what's actually makeable. Client thread only. */
	private int chosenQty()
	{
		final Recipe r = selectedRecipe();
		if (r == null)
		{
			return 0;
		}
		final int max = maxMakeable(r);
		final int want = QTY_OPTIONS[qtyChoice] < 0 ? max : QTY_OPTIONS[qtyChoice];
		return Math.min(want, max);
	}

	private int maxMakeable(Recipe r)
	{
		int max = Integer.MAX_VALUE;
		for (int[] mat : r.mats)
		{
			max = (int) Math.min(max, LofModal.carried(client, mat[0]) / Math.max(1, mat[1]));
		}
		return max == Integer.MAX_VALUE ? 0 : max;
	}

	private boolean levelOk(Recipe r)
	{
		return client.getBoostedSkillLevel(Skill.SMITHING) >= r.level;
	}

	/** How many recipe rows are actually drawn/clickable — capped so they clear the footer band. */

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
		if (!listRect(oxCached, oyCached).contains(p))
		{
			return false;
		}
		scroll = LofModal.clampScroll(scroll + rotation * ROW_STEP, recipes.size() * ROW_STEP, LIST_H);
		return true;
	}

	private Rectangle qtyRect(int ox, int oy, int i)
	{
		return LofModal.footerChip(ox, oy, LofModal.H, i, 48, 8);
	}

	private Rectangle makeRect(int ox, int oy)
	{
		return LofModal.footerButton(ox, oy, 170);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = oxCached, oy = oyCached; // cached on the client thread by render()
		if (!new Rectangle(ox, oy, LofModal.W, LofModal.H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		// The footer (quantity chips + MAKE) is tested BEFORE the recipe rows so a long recipe
		// list can never steal a click from the action buttons that share the lower panel band.
		for (int i = 0; i < QTY_OPTIONS.length; i++)
		{
			if (qtyRect(ox, oy, i).contains(p))
			{
				return QTY_BASE + i;
			}
		}
		if (makeRect(ox, oy).contains(p))
		{
			// Cached gate (client thread) — never re-read the inventory here.
			return canMakeCached ? MAKE : INSIDE;
		}
		// Rows are gated to the clipped list viewport (like the teleport list) so a row scrolled
		// out of view — or overlapping the footer band — can never take a click.
		if (listRect(ox, oy).contains(p))
		{
			final int rel = p.y - (oy + LIST_TOP) + scroll;
			final int i = rel / ROW_STEP;
			if (i >= 0 && i < recipes.size() && (rel - i * ROW_STEP) <= ROW_H)
			{
				return ROW_BASE + i;
			}
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
		oxCached = ox;
		oyCached = oy;
		final Point mouse = mousePoint();
		final String sub = "Smithing " + client.getBoostedSkillLevel(Skill.SMITHING);
		LofModal.frame(g, ox, oy, title, sub, mouse);

		g.setFont(FontManager.getRunescapeFont());
		final int contentH = recipes.size() * ROW_STEP;
		scroll = LofModal.clampScroll(scroll, contentH, LIST_H);
		final Shape listClip = g.getClip();
		g.setClip(ox + LofModal.PAD, oy + LIST_TOP, LofModal.W - 2 * LofModal.PAD, LIST_H);
		for (int i = 0; i < recipes.size(); i++)
		{
			final int ry = oy + LIST_TOP + i * ROW_STEP - scroll;
			if (ry + ROW_H < oy + LIST_TOP || ry > oy + LIST_TOP + LIST_H)
			{
				continue; // scrolled out of the viewport
			}
			drawRow(g, ox, oy, i, mouse);
		}
		g.setClip(listClip);
		LofModal.scrollbar(g, ox + LofModal.W - 10, oy + LIST_TOP, LIST_H, contentH, scroll);

		// selected recipe status line
		final Recipe sel = selectedRecipe();
		g.setFont(FontManager.getRunescapeSmallFont());
		if (sel != null)
		{
			final int max = maxMakeable(sel);
			final String status;
			final Color col;
			if (!levelOk(sel))
			{
				status = "Requires Smithing level " + sel.level + ".";
				col = new Color(255, 138, 117);
			}
			else if (max <= 0)
			{
				status = "You don't carry the materials for " + itemName(sel.resultId) + ".";
				col = new Color(255, 138, 117);
			}
			else
			{
				status = "Materials for " + max + " carried.";
				col = new Color(110, 205, 110);
			}
			LofModal.statusLine(g, ox, oy, status, col);
		}

		// quantity chips + MAKE
		for (int i = 0; i < QTY_OPTIONS.length; i++)
		{
			final boolean on = i == qtyChoice;
			final Rectangle qr = qtyRect(ox, oy, i);
			final String label = QTY_OPTIONS[i] < 0 ? "ALL" : String.valueOf(QTY_OPTIONS[i]);
			LofModal.button(g, qr, label, on ? LofTheme.GOLD : LofTheme.GOLD_DIM, true, qr.contains(mouse) || on);
		}
		// Publish the gate for the mouse thread in the same pass that draws it, so the button can
		// never look enabled while the click path disagrees.
		final int qty = sel == null ? 0 : chosenQty();
		final boolean canMake = sel != null && levelOk(sel) && qty > 0;
		qtyCached = qty;
		canMakeCached = canMake;
		final String makeLabel = canMake ? "MAKE " + qty : "MAKE";
		LofModal.button(g, makeRect(ox, oy), makeLabel, LofTheme.GOLD, canMake, makeRect(ox, oy).contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawRow(Graphics2D g, int ox, int oy, int i, Point mouse)
	{
		final Recipe r = recipes.get(i);
		final Rectangle row = rowRect(ox, oy, i);
		final boolean sel = i == selected;
		final boolean hov = row.contains(mouse);
		final boolean locked = !levelOk(r);

		g.setColor(sel ? LofTheme.alpha(LofTheme.GOLD, 20) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(row.x, row.y, row.width, row.height, 8, 8);
		if (sel)
		{
			g.setColor(LofTheme.alpha(LofTheme.GOLD, 130));
			g.drawRoundRect(row.x, row.y, row.width, row.height, 8, 8);
		}

		final java.awt.Composite oldComposite = g.getComposite();
		if (locked)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
		}

		drawItem(g, row.x + 6, row.y + 3, r.resultId);
		g.setFont(FontManager.getRunescapeFont());
		// Fitted to the space before the materials column so a long recipe name can't run into it.
		LofTheme.shadowText(g, LofModal.fit(g.getFontMetrics(), itemName(r.resultId), 200 - 42 - 8),
			row.x + 42, row.y + 21, locked ? LofTheme.TEXT_DIM : LofTheme.TEXT);

		// materials, drawn as icon ×qty pairs mid-row
		int mx = row.x + 200;
		g.setFont(FontManager.getRunescapeSmallFont());
		for (int[] mat : r.mats)
		{
			drawItemSmall(g, mx, row.y + 6, mat[0]);
			LofTheme.shadowText(g, "x" + mat[1], mx + 24, row.y + 22, LofTheme.TEXT_DIM);
			mx += 44;
		}

		final String meta = locked ? "lvl " + r.level : "lvl " + r.level + " · " + (r.xp10 / 10) + " xp";
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, meta, row.x + row.width - 10 - fm.stringWidth(meta), row.y + 21,
			locked ? new Color(255, 138, 117) : LofTheme.TEXT_DIM);

		g.setComposite(oldComposite);
	}

	private void drawItem(Graphics2D g, int x, int y, int itemId)
	{
		final java.awt.image.BufferedImage img = itemManager.getImage(itemId);
		if (img != null)
		{
			g.drawImage(img, x, y, 30, 27, null);
		}
	}

	private void drawItemSmall(Graphics2D g, int x, int y, int itemId)
	{
		final java.awt.image.BufferedImage img = itemManager.getImage(itemId);
		if (img != null)
		{
			g.drawImage(img, x, y, 22, 20, null);
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

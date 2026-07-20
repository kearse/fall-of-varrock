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
	// Rows must never reach the status line (H-56) or the footer buttons (H-44): a recipe row
	// drawn over the footer would both cover MAKE and — since it hit-tests first — swallow its
	// click. ROWS_Y(48) + n*38 + ROW_H(34) <= H-56(344) → n <= 6, so at most 7 rows (0..6) fit.
	private static final int MAX_ROWS = 7;

	private final Client client;
	private final ItemManager itemManager;

	private boolean visible;
	private String title = "Making";
	private List<Recipe> recipes = new ArrayList<>();
	private int selected;
	private int qtyChoice = 3; // default ALL

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

	/** The chosen quantity resolved against what's actually makeable. */
	int chosenQty()
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
	private int shownRows()
	{
		return Math.min(recipes.size(), MAX_ROWS);
	}

	private Rectangle rowRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD, oy + ROWS_Y + i * ROW_STEP, LofModal.W - 2 * LofModal.PAD, ROW_H);
	}

	private Rectangle qtyRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD + i * 56, oy + LofModal.H - 12 - 32, 48, 32);
	}

	private Rectangle makeRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.W - LofModal.PAD - 170, oy + LofModal.H - 12 - 32, 170, 32);
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
		// The footer (quantity chips + MAKE) is tested BEFORE the recipe rows so a long recipe
		// list can never steal a click from the action buttons that share the lower panel band.
		for (int i = 0; i < QTY_OPTIONS.length; i++)
		{
			if (qtyRect(ox, oy, i).contains(p))
			{
				return QTY_BASE + i;
			}
		}
		final Recipe r = selectedRecipe();
		if (r != null && levelOk(r) && chosenQty() > 0 && makeRect(ox, oy).contains(p))
		{
			return MAKE;
		}
		for (int i = 0; i < shownRows(); i++)
		{
			if (rowRect(ox, oy, i).contains(p))
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
		final Point mouse = mousePoint();
		final String sub = "Smithing " + client.getBoostedSkillLevel(Skill.SMITHING);
		LofModal.frame(g, ox, oy, title, sub, mouse);

		g.setFont(FontManager.getRunescapeFont());
		for (int i = 0; i < shownRows(); i++)
		{
			drawRow(g, ox, oy, i, mouse);
		}

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
			LofTheme.shadowText(g, status, ox + LofModal.PAD, oy + LofModal.H - 56, col);
		}

		// quantity chips + MAKE
		for (int i = 0; i < QTY_OPTIONS.length; i++)
		{
			final boolean on = i == qtyChoice;
			final Rectangle qr = qtyRect(ox, oy, i);
			final String label = QTY_OPTIONS[i] < 0 ? "ALL" : String.valueOf(QTY_OPTIONS[i]);
			LofModal.button(g, qr, label, on ? LofTheme.GOLD : LofTheme.GOLD_DIM, true, qr.contains(mouse) || on);
		}
		final boolean canMake = sel != null && levelOk(sel) && chosenQty() > 0;
		final String makeLabel = canMake ? "MAKE " + chosenQty() : "MAKE";
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
		LofTheme.shadowText(g, itemName(r.resultId), row.x + 42, row.y + 21, locked ? LofTheme.TEXT_DIM : LofTheme.TEXT);

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

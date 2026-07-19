/*
 * Fall of Varrock — War Contracts window (renderer + hit-testing).
 *
 * Two contract cards — the combat contract (skull-and-swords icon, kill progress bar, streak
 * pill) and the resource contract (loot-sack icon, gather count) — plus the new-contract
 * buttons (locked while one is active) and the reward-shop button. Per the approved design.
 */
package net.runelite.client.plugins.lofcontracts;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

class LofContractsOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int NEW_COMBAT = 2;
	static final int NEW_RESOURCE = 3;
	static final int REWARDS = 4;

	private static final int CARD_X_PAD = LofModal.PAD;
	private static final int COMBAT_Y = LofModal.TITLE_H + 12;
	private static final int CARD_H = 96;
	private static final int RESOURCE_Y = COMBAT_Y + CARD_H + 10;
	private static final int RESOURCE_H = 70;
	private static final int BTNS_Y = RESOURCE_Y + RESOURCE_H + 12;

	private final Client client;

	private boolean visible;
	private int streak;
	private long warEffort;
	private String combatName;
	private int combatLeft;
	private int combatTotal;
	private String resourceName;
	private int resourceLeft;
	private String resourceSkill = "-";

	private BufferedImage combatIcon;
	private BufferedImage resourceIcon;
	private boolean iconsLoaded;

	@Inject
	private LofContractsOverlay(Client client)
	{
		this.client = client;
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

	void setState(int streak, long warEffort, String combatName, int combatLeft, int combatTotal,
		String resourceName, int resourceLeft, String resourceSkill)
	{
		this.streak = streak;
		this.warEffort = warEffort;
		this.combatName = combatName;
		this.combatLeft = combatLeft;
		this.combatTotal = combatTotal;
		this.resourceName = resourceName;
		this.resourceLeft = resourceLeft;
		this.resourceSkill = resourceSkill;
	}

	boolean combatActive()
	{
		return combatName != null && combatLeft > 0;
	}

	boolean resourceActive()
	{
		return resourceName != null && resourceLeft > 0;
	}

	private Rectangle combatBtn(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.PAD, oy + BTNS_Y, (LofModal.W - 2 * LofModal.PAD - 10) / 2, 40);
	}

	private Rectangle resourceBtn(int ox, int oy)
	{
		final Rectangle c = combatBtn(ox, oy);
		return new Rectangle(c.x + c.width + 10, c.y, c.width, c.height);
	}

	private Rectangle rewardsBtn(int ox, int oy)
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
		if (!combatActive() && combatBtn(ox, oy).contains(p))
		{
			return NEW_COMBAT;
		}
		if (!resourceActive() && resourceBtn(ox, oy).contains(p))
		{
			return NEW_RESOURCE;
		}
		if (rewardsBtn(ox, oy).contains(p))
		{
			return REWARDS;
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
		ensureIcons();

		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		final Point mouse = mousePoint();
		LofModal.frame(g, ox, oy, "War Contracts", "Vannaka · War Effort: " + LofModal.fmt(warEffort), mouse);

		drawCombatCard(g, ox, oy);
		drawResourceCard(g, ox, oy);

		LofModal.button(g, combatBtn(ox, oy),
			combatActive() ? "Combat contract active" : "New combat contract",
			LofTheme.GOLD, !combatActive(), combatBtn(ox, oy).contains(mouse));
		LofModal.button(g, resourceBtn(ox, oy),
			resourceActive() ? "Resource contract active" : "New resource contract",
			LofTheme.GOLD, !resourceActive(), resourceBtn(ox, oy).contains(mouse));

		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Back-to-back contracts keep your streak — the tougher the contract, the better the pay.",
			ox + LofModal.PAD, oy + LofModal.H - 46, LofTheme.TEXT_DIM);
		LofModal.button(g, rewardsBtn(ox, oy), "Open reward shop", LofTheme.GOLD, true, rewardsBtn(ox, oy).contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawCombatCard(Graphics2D g, int ox, int oy)
	{
		final Rectangle r = new Rectangle(ox + CARD_X_PAD, oy + COMBAT_Y, LofModal.W - 2 * CARD_X_PAD, CARD_H);
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);

		if (combatIcon != null)
		{
			g.drawImage(combatIcon, r.x + 10, r.y + 10, 38, 38, null);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		if (combatActive())
		{
			LofTheme.shadowText(g, combatName, r.x + 58, r.y + 26, LofTheme.TEXT);
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "Combat contract · assigned by Vannaka", r.x + 58, r.y + 42, LofTheme.TEXT_DIM);
			if (streak > 0)
			{
				final FontMetrics fm = g.getFontMetrics();
				LofTheme.pill(g, fm, "streak x" + streak, r.x + r.width - 10, r.y + 24, new Color(255, 138, 117));
			}
			// progress bar
			final int done = Math.max(0, combatTotal - combatLeft);
			LofTheme.shadowText(g, "Progress", r.x + 12, r.y + 66, LofTheme.GOLD_DIM);
			final String pv = done + " / " + combatTotal + " slain";
			LofTheme.shadowText(g, pv, r.x + r.width - 12 - g.getFontMetrics().stringWidth(pv), r.y + 66, LofTheme.GOLD);
			g.setColor(new Color(12, 10, 9));
			g.fillRoundRect(r.x + 12, r.y + 72, r.width - 24, 12, 6, 6);
			g.setColor(LofTheme.EMBER_DARK);
			g.drawRoundRect(r.x + 12, r.y + 72, r.width - 24, 12, 6, 6);
			final int fillW = combatTotal > 0 ? (r.width - 24) * done / combatTotal : 0;
			if (fillW > 2)
			{
				g.setColor(LofTheme.EMBER);
				g.fillRoundRect(r.x + 12, r.y + 72, fillW, 12, 6, 6);
			}
		}
		else
		{
			LofTheme.shadowText(g, "No combat contract", r.x + 58, r.y + 26, LofTheme.TEXT_DIM);
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "The realm needs beasts put down — take a contract below.", r.x + 58, r.y + 42, LofTheme.TEXT_DIM);
			if (streak > 0)
			{
				final FontMetrics fm = g.getFontMetrics();
				LofTheme.pill(g, fm, "streak x" + streak, r.x + r.width - 10, r.y + 24, LofTheme.GOLD_DIM);
			}
		}
	}

	private void drawResourceCard(Graphics2D g, int ox, int oy)
	{
		final Rectangle r = new Rectangle(ox + CARD_X_PAD, oy + RESOURCE_Y, LofModal.W - 2 * CARD_X_PAD, RESOURCE_H);
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);

		if (resourceIcon != null)
		{
			g.drawImage(resourceIcon, r.x + 12, r.y + 10, 32, 32, null);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		if (resourceActive())
		{
			LofTheme.shadowText(g, resourceLeft + " " + resourceName + " to gather", r.x + 58, r.y + 26, LofTheme.TEXT);
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "Resource contract · " + resourceSkill + " work — completes as you gather, you keep the goods.",
				r.x + 58, r.y + 44, LofTheme.TEXT_DIM);
		}
		else
		{
			LofTheme.shadowText(g, "No resource contract", r.x + 58, r.y + 26, LofTheme.TEXT_DIM);
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "Mine, chop or fish for the war effort between fights.", r.x + 58, r.y + 44, LofTheme.TEXT_DIM);
		}
	}

	private void ensureIcons()
	{
		if (iconsLoaded)
		{
			return;
		}
		iconsLoaded = true;
		try
		{
			combatIcon = ImageUtil.loadImageResource(LofContractsOverlay.class, "contract_combat.png");
		}
		catch (Exception e)
		{
			combatIcon = null;
		}
		try
		{
			resourceIcon = ImageUtil.loadImageResource(LofContractsOverlay.class, "contract_resource.png");
		}
		catch (Exception e)
		{
			resourceIcon = null;
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

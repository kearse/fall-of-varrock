/*
 * Fall of Varrock — Bond Exchange window (renderer + hit-testing).
 *
 * The wallet up top (tradeable = ember-seal icon, claimed = sealed-scroll icon) with a compact
 * "Claim one" button, the two redemption cards (30 days membership / 450 Donor Points, each with
 * its store icon), the permanent-claim warning, and the "never pay to be a member" story printed
 * on the window. Per the approved design (v3.1 button fix included).
 */
package net.runelite.client.plugins.lofbonds;

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

class LofBondsOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int CLAIM = 2;
	static final int MEMBER = 3;
	static final int DONOR = 4;

	private static final int WALLET_Y = LofModal.TITLE_H + 12;
	private static final int WALLET_H = 52;
	private static final int CARDS_Y = WALLET_Y + WALLET_H + 12;
	private static final int CARD_H = 132;

	private final Client client;

	private boolean visible;
	private int tradeable;
	private int claimed;

	private BufferedImage tradeableIcon;
	private BufferedImage claimedIcon;
	private BufferedImage memberIcon;
	private BufferedImage donorIcon;
	private boolean iconsLoaded;

	@Inject
	private LofBondsOverlay(Client client)
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

	void setWallet(int tradeable, int claimed)
	{
		this.tradeable = tradeable;
		this.claimed = claimed;
	}

	private Rectangle walletBox(int ox, int oy, int i)
	{
		final int w = (LofModal.W - 2 * LofModal.PAD - 10) / 2;
		return new Rectangle(ox + LofModal.PAD + i * (w + 10), oy + WALLET_Y, w, WALLET_H);
	}

	/** Sits in the right of the tradeable-bonds box — narrow enough to leave the box's own label
	 *  room to print in full (it used to be 96px wide and covered "TRADEABLE BONDS"). */
	private Rectangle claimRect(int ox, int oy)
	{
		final Rectangle box = walletBox(ox, oy, 0);
		return new Rectangle(box.x + box.width - 78, box.y + (box.height - 24) / 2, 70, 24);
	}

	private Rectangle cardRect(int ox, int oy, int i)
	{
		final int w = (LofModal.W - 2 * LofModal.PAD - 10) / 2;
		return new Rectangle(ox + LofModal.PAD + i * (w + 10), oy + CARDS_Y, w, CARD_H);
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
		if (tradeable > 0 && claimRect(ox, oy).contains(p))
		{
			return CLAIM;
		}
		if (claimed > 0 && cardRect(ox, oy, 0).contains(p))
		{
			return MEMBER;
		}
		if (claimed > 0 && cardRect(ox, oy, 1).contains(p))
		{
			return DONOR;
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
		LofModal.frame(g, ox, oy, "The Bond Exchange", "Bond Merchant", mouse);

		// wallet — the left box carries the Claim button, so its text is fitted to the space before
		// it (the label used to be drawn full-width and the button sat straight on top of it).
		final boolean claimable = tradeable > 0;
		drawWalletBox(g, ox, oy, 0, tradeableIcon, tradeable, "TRADEABLE",
			claimable ? claimRect(ox, oy).x - walletBox(ox, oy, 0).x - 54 - 8 : Integer.MAX_VALUE);
		drawWalletBox(g, ox, oy, 1, claimedIcon, claimed, "CLAIMED", Integer.MAX_VALUE);
		if (claimable)
		{
			final Rectangle cb = claimRect(ox, oy);
			LofModal.button(g, cb, "Claim", LofTheme.GOLD, true, cb.contains(mouse));
		}

		// redemption cards
		drawCard(g, ox, oy, 0, memberIcon, "30 days", "bronze membership", mouse);
		drawCard(g, ox, oy, 1, donorIcon, "450", "Donor Points", mouse);

		// warnings + the story
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Claiming is permanent — a claimed bond can never be traded again.",
			ox + LofModal.PAD, oy + CARDS_Y + CARD_H + 22, new Color(255, 138, 117));
		LofTheme.shadowText(g, "Bonds are bought for cash on the website — or from other players for gold.",
			ox + LofModal.PAD, oy + LofModal.H - 40, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "You never have to pay to be a member. Bonds never redeem for gold.",
			ox + LofModal.PAD, oy + LofModal.H - 24, LofTheme.TEXT_DIM);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawWalletBox(Graphics2D g, int ox, int oy, int i, BufferedImage icon, int count,
		String label, int textRoom)
	{
		final Rectangle r = walletBox(ox, oy, i);
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
		if (icon != null)
		{
			g.drawImage(icon, r.x + 10, r.y + (r.height - 34) / 2, 34, 34, null);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, String.valueOf(count), r.x + 54, r.y + 24, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, LofModal.fit(g.getFontMetrics(), label, textRoom),
			r.x + 54, r.y + 40, LofTheme.TEXT_DIM);
	}

	private void drawCard(Graphics2D g, int ox, int oy, int i, BufferedImage icon, String big, String small, Point mouse)
	{
		final Rectangle r = cardRect(ox, oy, i);
		final boolean enabled = claimed > 0;
		final boolean hov = enabled && r.contains(mouse);
		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
		g.setColor(hov ? LofTheme.alpha(LofTheme.GOLD, 150) : LofTheme.alpha(LofTheme.GOLD, 60));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);

		final java.awt.Composite oldComposite = g.getComposite();
		if (!enabled)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
		}
		if (icon != null)
		{
			g.drawImage(icon, r.x + (r.width - 44) / 2, r.y + 12, 44, 44, null);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, big, r.x + (r.width - fm.stringWidth(big)) / 2, r.y + 78, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		fm = g.getFontMetrics();
		LofTheme.shadowText(g, small, r.x + (r.width - fm.stringWidth(small)) / 2, r.y + 94, LofTheme.TEXT_DIM);
		final String price = "1 claimed bond";
		LofTheme.pill(g, fm, price, r.x + (r.width + fm.stringWidth(price) + 16) / 2, r.y + 116, LofTheme.GOLD);
		g.setComposite(oldComposite);
	}

	private void ensureIcons()
	{
		if (iconsLoaded)
		{
			return;
		}
		iconsLoaded = true;
		tradeableIcon = load("bond_tradeable.png");
		claimedIcon = load("bond_claimed.png");
		memberIcon = load("card_membership.png");
		donorIcon = load("card_donor.png");
	}

	private BufferedImage load(String name)
	{
		try
		{
			return ImageUtil.loadImageResource(LofBondsOverlay.class, name);
		}
		catch (Exception e)
		{
			return null;
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

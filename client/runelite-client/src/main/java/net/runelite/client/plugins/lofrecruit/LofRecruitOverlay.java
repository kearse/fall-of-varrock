/*
 * Fall of Varrock — Muster Companions window (renderer + hit-testing).
 *
 * Three discipline cards (melee / ranged / mage) + the banner strip showing the fielded roster.
 * States: recruitable, rank-locked (below Knight — cards dim, path shown), banner full, and
 * short-of-coin. All icons are the website's real art (discipline_melee.png = forum/war.png
 * crossed polearms; banner_horn.png = icon-warroom.png; archer/mage deliberately re-use
 * forum/media + store-bonds until their bespoke art is generated — same filenames, drop-in).
 *
 * Roster chips read the companion status varps (4613-4615, packed by CompanionRegistry:
 * bit0 present · bits 7-14 combat · bits 25-26 style) so the strip works without the side panel.
 */
package net.runelite.client.plugins.lofrecruit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
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
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

class LofRecruitOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int CARD_BASE = 100; // +0 melee, +1 range, +2 mage

	static final String[] STYLE_TOKENS = {"melee", "range", "mage"};
	private static final String[] STYLE_NAMES = {"Melee Soldier", "Ranged Soldier", "Mage Soldier"};
	private static final String[][] STYLE_DESC = {
		{"Sword & shield at your side.", "Holds the line in the thick", "of it."},
		{"Longbow from the second rank.", "Picks targets before they", "reach you."},
		{"Battle-caster. Autocasts and", "scales spells as they train.", ""},
	};

	/** Companion cap per Title ordinal — mirror of the server Title enum's companions field. */
	private static final int[] CAP_BY_TITLE = {0, 0, 0, 0, 1, 2, 3, 3};
	private static final String[] TITLE_NAMES = {"Peasant", "Commoner", "Squire", "Soldier", "Knight", "Lord", "Minister", "King"};

	private static final int RECRUIT_COST = 1_000_000; // mirror of server RecruitMenu.RECRUIT_COST
	private static final int COINS_ID = 995;
	private static final int COMP_STATUS_BASE = 4613;  // CompanionRegistry status varps
	private static final int MAX_COMPANIONS = 3;

	// Design-system standard modal (480x400 — docs/overlay-design-system.md §6A).
	private static final int WIN_W = 480;
	private static final int WIN_H = 400;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 14;

	private static final int CARDS_Y = TITLE_H + 12;   // 50
	private static final int CARD_H = 168;
	private static final int CARD_GAP = 10;
	private static final int STRIP_Y = CARDS_Y + CARD_H + 12;
	private static final int STRIP_H = 34;

	private final Client client;
	private final LofRecruitConfig config;

	private boolean visible;
	private int count;
	private int cap;
	private int titleOrdinal;

	private BufferedImage[] icons;
	private BufferedImage horn;
	private boolean iconsLoaded;

	@Inject
	private LofRecruitOverlay(Client client, LofRecruitConfig config)
	{
		this.client = client;
		this.config = config;
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

	void setState(int count, int cap, int titleOrdinal)
	{
		this.count = count;
		this.cap = cap;
		this.titleOrdinal = Math.min(Math.max(titleOrdinal, 0), TITLE_NAMES.length - 1);
	}

	/** Cards accept clicks only when a muster could actually go through. */
	boolean recruitable()
	{
		return cap > 0 && count < cap && coinsCarried() >= RECRUIT_COST;
	}

	private int originX()
	{
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, 512);
		return Math.max(0, Math.min((canvasW - WIN_W) / 2, (viewportW - WIN_W) / 2));
	}

	private int originY()
	{
		return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2);
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle cardRect(int ox, int oy, int i)
	{
		final int w = (WIN_W - 2 * PAD - 2 * CARD_GAP) / 3;
		return new Rectangle(ox + PAD + i * (w + CARD_GAP), oy + CARDS_Y, w, CARD_H);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (recruitable())
		{
			for (int i = 0; i < 3; i++)
			{
				if (cardRect(ox, oy, i).contains(p))
				{
					return CARD_BASE + i;
				}
			}
		}
		return INSIDE;
	}

	private long coinsCarried()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : inv.getItems())
		{
			if (item != null && item.getId() == COINS_ID)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		ensureIcons();

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();
		final long coins = coinsCarried();
		final boolean locked = cap == 0;
		final boolean full = cap > 0 && count >= cap;
		final boolean active = recruitable();

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);
		drawHeader(g, ox, oy, mouse);

		for (int i = 0; i < 3; i++)
		{
			drawCard(g, ox, oy, i, active, active && cardRect(ox, oy, i).contains(mouse));
		}

		drawBannerStrip(g, ox, oy);

		// centred state note under the strip
		g.setFont(FontManager.getRunescapeFont());
		if (locked)
		{
			final String firstRank = TITLE_NAMES[firstRankWithCompanions()];
			drawCentred(g, ox, oy + STRIP_Y + STRIP_H + 34, "Only those of rank may lead soldiers.", LofTheme.TEXT_DIM);
			drawCentred(g, ox, oy + STRIP_Y + STRIP_H + 52, "Rise to " + firstRank + " — see Duke Horacio in the castle.", LofTheme.GOLD);
		}
		else if (full)
		{
			drawCentred(g, ox, oy + STRIP_Y + STRIP_H + 34, "Your banner is full — a " + TITLE_NAMES[titleOrdinal] + " may field " + cap + ".", LofTheme.TEXT_DIM);
			final int higher = nextRankWithMoreCompanions();
			if (higher >= 0)
			{
				drawCentred(g, ox, oy + STRIP_Y + STRIP_H + 52, "Rise to " + TITLE_NAMES[higher] + " and the General will muster you another.", LofTheme.GOLD);
			}
		}
		else if (coins < RECRUIT_COST)
		{
			drawCentred(g, ox, oy + STRIP_Y + STRIP_H + 34, "A trained soldier costs " + fmt(RECRUIT_COST) + " coins.", new Color(255, 138, 117));
		}

		// status + footer
		g.setFont(FontManager.getRunescapeSmallFont());
		final String status = locked || cap == 0
			? "Companions fight, train and carry your banner into the war."
			: "A " + TITLE_NAMES[titleOrdinal] + " may field " + cap + " companion" + (cap > 1 ? "s" : "") + ". Manage them with the Companions panel.";
		LofTheme.shadowText(g, status, ox + PAD, oy + WIN_H - 40, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "Coins carried: " + fmt(coins), ox + PAD, oy + WIN_H - 18, LofTheme.TEXT_DIM);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawHeader(Graphics2D g, int ox, int oy, Point mouse)
	{
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Muster Companions", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = "General Zo  ·  banner " + count + " of " + cap;
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		final Rectangle cr = closeRect(ox, oy);
		final boolean hov = cr.contains(mouse);
		g.setColor(hov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(hov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);
	}

	private void drawCard(Graphics2D g, int ox, int oy, int i, boolean active, boolean hover)
	{
		final Rectangle r = cardRect(ox, oy, i);

		g.setColor(hover ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
		g.setColor(hover ? LofTheme.alpha(LofTheme.EMBER, 150) : new Color(255, 255, 255, 15));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);

		final java.awt.Composite oldComposite = g.getComposite();
		if (!active)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
		}

		drawDisciplineIcon(g, r.x + (r.width - 46) / 2, r.y + 12, 46, i);

		g.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, STYLE_NAMES[i], r.x + (r.width - fm.stringWidth(STYLE_NAMES[i])) / 2, r.y + 78,
			active ? LofTheme.GOLD : LofTheme.TEXT_DIM);

		g.setFont(FontManager.getRunescapeSmallFont());
		fm = g.getFontMetrics();
		int ly = r.y + 96;
		for (String line : STYLE_DESC[i])
		{
			if (!line.isEmpty())
			{
				LofTheme.shadowText(g, line, r.x + (r.width - fm.stringWidth(line)) / 2, ly, LofTheme.TEXT_DIM);
			}
			ly += 14;
		}

		final String cost = fmt(RECRUIT_COST) + " coins";
		LofTheme.shadowText(g, cost, r.x + (r.width - fm.stringWidth(cost)) / 2, r.y + r.height - 12,
			active ? LofTheme.GOLD : LofTheme.TEXT_DIM);

		g.setComposite(oldComposite);
	}

	private void drawBannerStrip(Graphics2D g, int ox, int oy)
	{
		final int x = ox + PAD, w = WIN_W - 2 * PAD;
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(x, oy + STRIP_Y, w, STRIP_H, 8, 8);

		int cx = x + 8;
		if (horn != null)
		{
			g.drawImage(horn, cx, oy + STRIP_Y + (STRIP_H - 24) / 2, 24, 24, null);
			cx += 30;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "YOUR BANNER", cx, oy + STRIP_Y + STRIP_H / 2 + 4, LofTheme.GOLD_DIM);
		cx += g.getFontMetrics().stringWidth("YOUR BANNER") + 12;

		// slot pills: fielded companions from the status varps, then dashed empties up to the cap
		final FontMetrics fm = g.getFontMetrics();
		final int slots = Math.min(Math.max(cap, count), MAX_COMPANIONS);
		for (int slot = 0; slot < slots; slot++)
		{
			final int status = client.getVarpValue(COMP_STATUS_BASE + slot);
			final String label;
			final boolean present = (status & 1) != 0;
			if (present)
			{
				final int combat = (status >> 7) & 0xFF;
				final int style = (status >> 25) & 0x3;
				label = STYLE_TOKENS[Math.min(style, 2)] + " · lvl " + combat;
			}
			else
			{
				label = "empty slot";
			}
			final int pw = fm.stringWidth(label) + 18;
			final int py = oy + STRIP_Y + (STRIP_H - 20) / 2;
			if (cx + pw > x + w - 6)
			{
				break; // never draw outside the strip
			}
			if (present)
			{
				g.setColor(LofTheme.alpha(LofTheme.GOLD, 22));
				g.fillRoundRect(cx, py, pw, 20, 20, 20);
				g.setColor(LofTheme.alpha(LofTheme.GOLD, 70));
				g.drawRoundRect(cx, py, pw, 20, 20, 20);
				LofTheme.shadowText(g, label, cx + 9, py + 14, LofTheme.TEXT);
			}
			else
			{
				final Stroke oldStroke = g.getStroke();
				g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 0, new float[]{3, 3}, 0));
				g.setColor(LofTheme.alpha(LofTheme.TEXT_DIM, 120));
				g.drawRoundRect(cx, py, pw, 20, 20, 20);
				g.setStroke(oldStroke);
				LofTheme.shadowText(g, label, cx + 9, py + 14, LofTheme.TEXT_DIM);
			}
			cx += pw + 8;
		}
	}

	// ---------------------------------- icons ----------------------------------

	private void ensureIcons()
	{
		if (iconsLoaded)
		{
			return;
		}
		iconsLoaded = true;
		icons = new BufferedImage[3];
		final String[] names = {"discipline_melee", "discipline_archer", "discipline_mage"};
		for (int i = 0; i < names.length; i++)
		{
			try
			{
				icons[i] = ImageUtil.loadImageResource(LofRecruitOverlay.class, names[i] + ".png");
			}
			catch (Exception e)
			{
				icons[i] = null; // missing resource — the slot simply draws empty
			}
		}
		try
		{
			horn = ImageUtil.loadImageResource(LofRecruitOverlay.class, "banner_horn.png");
		}
		catch (Exception e)
		{
			horn = null;
		}
	}

	/**
	 * Draw discipline [i]'s icon PNG. All three ship with real art from the website's icon family
	 * (melee = forum/war crossed polearms; archer/mage deliberately re-use the eye and ember-seal
	 * icons until their bespoke art is generated — same filenames, so it drops straight in).
	 * A missing resource draws nothing rather than a placeholder.
	 */
	private void drawDisciplineIcon(Graphics2D g, int x, int y, int size, int i)
	{
		if (icons[i] != null)
		{
			g.drawImage(icons[i], x, y, size, size, null);
		}
	}

	private int firstRankWithCompanions()
	{
		for (int i = 0; i < CAP_BY_TITLE.length; i++)
		{
			if (CAP_BY_TITLE[i] > 0)
			{
				return i;
			}
		}
		return CAP_BY_TITLE.length - 1;
	}

	private int nextRankWithMoreCompanions()
	{
		for (int i = titleOrdinal + 1; i < CAP_BY_TITLE.length; i++)
		{
			if (CAP_BY_TITLE[i] > cap)
			{
				return i;
			}
		}
		return -1;
	}

	private void drawCentred(Graphics2D g, int ox, int y, String text, Color col)
	{
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, text, ox + (WIN_W - fm.stringWidth(text)) / 2, y, col);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static String fmt(long n)
	{
		return String.format("%,d", n);
	}
}

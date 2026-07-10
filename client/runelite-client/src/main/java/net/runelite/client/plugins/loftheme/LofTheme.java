/*
 * Fall of Varrock — shared brand theme for our custom client overlays.
 *
 * One place for the palette (ember red + antique gold on warm near-black, matching the
 * login screen and website), the shield logo, and the panel/pill/text drawing helpers.
 * Every lof* overlay should style itself from here so the client reads as one product.
 *
 * Not a plugin — just a static utility; PluginManager's ClassGraph scan only registers
 * Plugin subclasses, so this package is safe to live under .plugins.
 */
package net.runelite.client.plugins.loftheme;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

public final class LofTheme
{
	// warm near-black surfaces (login-screen family)
	public static final Color PANEL = new Color(21, 17, 16, 240);
	public static final Color PANEL_OPAQUE = new Color(21, 17, 16);
	public static final Color HEADER = new Color(32, 24, 22);
	public static final Color SHADOW = new Color(0, 0, 0, 110);

	// ember / crimson accent (the lava crack + login borders)
	public static final Color EMBER = new Color(203, 56, 38);
	public static final Color EMBER_DARK = new Color(120, 30, 24);
	public static final Color LAVA = new Color(255, 120, 40);

	// antique gold (the LOG IN button + headings)
	public static final Color GOLD = new Color(232, 193, 90);
	public static final Color GOLD_DIM = new Color(166, 138, 76);

	public static final Color TEXT = new Color(240, 235, 228);
	public static final Color TEXT_DIM = new Color(158, 149, 140);
	public static final Color ROW = new Color(255, 255, 255, 10);
	public static final Color ROW_HOVER = new Color(255, 220, 170, 26);

	private static final BufferedImage LOGO = ImageUtil.loadImageResource(LofTheme.class, "logo.png");

	private LofTheme()
	{
	}

	/** The 48px shield logo (never null in a built jar; callers should still null-check for safety). */
	public static BufferedImage logo()
	{
		return LOGO;
	}

	/** Drop shadow + rounded panel + ember border — the standard window/HUD backing. */
	public static void panel(Graphics2D g, int x, int y, int w, int h, int arc)
	{
		g.setColor(SHADOW);
		g.fillRoundRect(x + 3, y + 4, w, h, arc + 4, arc + 4);
		g.setColor(PANEL);
		g.fillRoundRect(x, y, w, h, arc, arc);
		g.setColor(new Color(EMBER_DARK.getRed(), EMBER_DARK.getGreen(), EMBER_DARK.getBlue(), 210));
		g.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
	}

	/** 2px ember underline fading out to the right — sits under headers/titles. */
	public static void emberUnderline(Graphics2D g, int x, int y, int w)
	{
		g.setPaint(new GradientPaint(x, 0, EMBER, x + w, 0, new Color(EMBER.getRed(), EMBER.getGreen(), EMBER.getBlue(), 0)));
		g.fillRect(x, y, w, 2);
	}

	/** Text with the classic 1px black drop shadow. */
	public static void shadowText(Graphics2D g, String text, int x, int y, Color col)
	{
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(col);
		g.drawString(text, x, y);
	}

	/** Right-aligned rounded tag: coloured text on a translucent fill of the same hue. */
	public static void pill(Graphics2D g, FontMetrics fm, String text, int rightX, int textBaselineY, Color col)
	{
		final int tw = fm.stringWidth(text);
		final int px = rightX - tw - 16;
		final int py = textBaselineY - fm.getAscent() - 2;
		final int ph = fm.getHeight() + 4;
		g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 38));
		g.fillRoundRect(px, py, tw + 16, ph, ph, ph);
		g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 90));
		g.drawRoundRect(px, py, tw + 16, ph, ph, ph);
		g.setColor(col);
		g.drawString(text, px + 8, textBaselineY);
	}

	/** col with its alpha replaced. */
	public static Color alpha(Color col, int a)
	{
		return new Color(col.getRed(), col.getGreen(), col.getBlue(), a);
	}
}

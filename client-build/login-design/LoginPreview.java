/*
 * Fall of Varrock — custom login screen DESIGN preview.
 *
 * Renders the Tier-2 login layout to a PNG so we can lock the look before wiring
 * it into the client. The real thing becomes a Swing panel over the game canvas
 * (real JTextFields / JButton), but the framing, background and styling are these.
 *
 *   javac LoginPreview.java && java LoginPreview out.png
 */
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class LoginPreview {
    static final int W = 765, H = 503;
    static final Color DARK    = new Color(0x08, 0x05, 0x06);
    static final Color GOLD    = new Color(0xE8, 0xC9, 0x7A);
    static final Color GOLD_HI = new Color(0xF6, 0xE4, 0xB0);
    static final Color RED     = new Color(0xDC, 0x26, 0x26);
    static final Color PARCH   = new Color(0xE8, 0xD8, 0xB0);
    static final Color FIELDBG = new Color(0x12, 0x0C, 0x0D);

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // --- background: hero image cover-cropped + dark/red overlay ---
        BufferedImage hero = ImageIO.read(new File("C:/Program Files (x86)/Kearse RSPS/client-build/assets/hero-bg.jpg"));
        double sc = Math.max(W / (double) hero.getWidth(), H / (double) hero.getHeight());
        int dw = (int) (hero.getWidth() * sc), dh = (int) (hero.getHeight() * sc);
        g.drawImage(hero, (W - dw) / 2, (H - dh) / 2, dw, dh, null);
        g.setColor(new Color(8, 5, 6, 150));
        g.fillRect(0, 0, W, H);
        paintVertical(g, 0, 0, W, 150, new Color(8,5,6,190), new Color(8,5,6,0));
        paintVertical(g, 0, H - 170, W, 170, new Color(8,5,6,0), new Color(8,5,6,230));
        paintRadialRed(g);

        // --- logo (the homepage hero mark) ---
        BufferedImage logo = ImageIO.read(new File("C:/Program Files (x86)/Kearse RSPS/client-build/assets/logo-full-trim.png"));
        int lw = 244, lh = (int) (logo.getHeight() * (lw / (double) logo.getWidth()));
        g.drawImage(logo, (W - lw) / 2, 2, lw, lh, null);

        // --- login panel ---
        int pw = 340, ph = 208, px = (W - pw) / 2, py = 182;
        panel(g, px, py, pw, ph);

        int fx = px + 28, fw = pw - 56;
        label(g, "USERNAME", fx, py + 34);
        link(g, "Register?", px + pw - 28, py + 34, true);
        field(g, fx, py + 42, fw, 30, "");

        label(g, "PASSWORD", fx, py + 92);
        link(g, "Forgot?", px + pw - 28, py + 92, true);
        field(g, fx, py + 100, fw, 30, "•••••••••");

        goldButton(g, fx, py + 146, fw, 38, "LOG IN");

        // remember me
        g.setColor(GOLD);
        g.drawRect(fx, py + 192, 12, 12);
        g.setColor(GOLD_HI);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("✓", fx + 2, py + 202);
        g.setColor(PARCH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("Remember me", fx + 20, py + 202);

        // --- saved accounts ---
        int sy = py + ph + 18;
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        drawCentered(g, "SAVED ACCOUNTS", W / 2, sy, GOLD, null);
        String[] names = {"BizzyZ", "Bizzy", "+ New"};
        int tw = 92, th = 46, gap = 12;
        int total = names.length * tw + (names.length - 1) * gap;
        int sx = (W - total) / 2;
        for (int i = 0; i < names.length; i++) {
            accountTile(g, sx + i * (tw + gap), sy + 10, tw, th, names[i], i == names.length - 1);
        }

        g.dispose();
        ImageIO.write(img, "png", new File(args.length > 0 ? args[0] : "login-preview.png"));
        System.out.println("wrote " + (args.length > 0 ? args[0] : "login-preview.png"));
    }

    static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(10, 7, 8, 235));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 16, 16));
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x5a, 0x2a, 0x2a));
        g.draw(new RoundRectangle2D.Float(x + 1, y + 1, w - 2, h - 2, 15, 15));
        g.setColor(new Color(0xDC, 0x26, 0x26, 90));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x + 3, y + 3, w - 6, h - 6, 13, 13));
    }

    static void label(Graphics2D g, String s, int x, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(GOLD);
        g.drawString(s, x, y);
    }

    static void link(Graphics2D g, String s, int rightX, int y, boolean right) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(0xB9, 0xA8, 0x86));
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, rightX - w, y);
    }

    static void field(Graphics2D g, int x, int y, int w, int h, String text) {
        g.setColor(FIELDBG);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 6, 6));
        g.setColor(new Color(0x3a, 0x30, 0x22));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 6, 6));
        if (!text.isEmpty()) {
            g.setColor(PARCH);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString(text, x + 10, y + 20);
        }
        // caret
        g.setColor(GOLD);
        g.fillRect(x + 10 + g.getFontMetrics(new Font("SansSerif", Font.PLAIN, 14)).stringWidth(text) + 1, y + 7, 1, h - 14);
    }

    static void goldButton(Graphics2D g, int x, int y, int w, int h, String s) {
        paintVerticalRR(g, x, y, w, h, 8, GOLD_HI, new Color(0xC8, 0x9A, 0x3C));
        g.setColor(new Color(0x6b, 0x4a, 0x12));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
        g.setFont(new Font("Serif", Font.BOLD, 20));
        drawCentered(g, s, x + w / 2, y + h / 2 + 7, new Color(0x2a, 0x1c, 0x06), null);
    }

    static void accountTile(Graphics2D g, int x, int y, int w, int h, String name, boolean isNew) {
        g.setColor(new Color(12, 8, 9, 220));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
        g.setColor(isNew ? new Color(0x3a, 0x30, 0x22) : new Color(0x5a, 0x2a, 0x2a));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
        // avatar circle
        g.setColor(isNew ? new Color(0x2a, 0x24, 0x18) : new Color(0x3a, 0x1a, 0x1a));
        g.fillOval(x + 8, y + 10, 26, 26);
        g.setColor(isNew ? GOLD : PARCH);
        g.setFont(new Font("SansSerif", Font.BOLD, isNew ? 18 : 14));
        String ic = isNew ? "+" : name.substring(0, 1);
        int iw = g.getFontMetrics().stringWidth(ic);
        g.drawString(ic, x + 8 + 13 - iw / 2, y + 10 + 18);
        g.setColor(PARCH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(name, x + 42, y + 28);
    }

    // --- helpers ---
    static void drawCentered(Graphics2D g, String s, int cx, int by, Color c, Color shadow) {
        int w = g.getFontMetrics().stringWidth(s);
        if (shadow != null) { g.setColor(shadow); g.drawString(s, cx - w / 2 + 2, by + 2); }
        g.setColor(c);
        g.drawString(s, cx - w / 2, by);
    }

    static void paintVertical(Graphics2D g, int x, int y, int w, int h, Color top, Color bot) {
        g.setPaint(new GradientPaint(0, y, top, 0, y + h, bot));
        g.fillRect(x, y, w, h);
    }

    static void paintVerticalRR(Graphics2D g, int x, int y, int w, int h, int r, Color top, Color bot) {
        g.setPaint(new GradientPaint(0, y, top, 0, y + h, bot));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, r, r));
    }

    static void paintRadialRed(Graphics2D g) {
        // Radius must reach the bottom so the glow fades out smoothly
        // instead of ending in a hard edge partway down.
        RadialGradientPaint rp = new RadialGradientPaint(
            new Point(W / 2, 30), Math.max(380, Math.max(W / 2, H)),
            new float[]{0f, 1f},
            new Color[]{new Color(0xDC, 0x26, 0x26, 70), new Color(0xDC, 0x26, 0x26, 0)});
        g.setPaint(rp);
        g.fillRect(0, 0, W, H);
    }
}

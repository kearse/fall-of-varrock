/*
 * Fall of Varrock — custom login screen renderer + input controller.
 *
 * Drawn from Hooks.draw() when GameState == LOGIN_SCREEN (RuneLite's overlay system
 * skips the login screen). Mouse/Key listener: click a field to focus, type to fill,
 * click LOG IN / press Enter to log in, click a saved account to fill+login.
 */
package net.runelite.client.ui.login;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@Singleton
public class LofLoginRenderer implements MouseListener, KeyListener
{
	private static final int DW = 765, DH = 503;
	private static final int FOCUS_NONE = 0, FOCUS_USER = 1, FOCUS_PASS = 2;
	private static final int MAX_LEN = 30;
	private static final int MAX_VISIBLE = 3;

	private static final Color GOLD = new Color(0xE8, 0xC9, 0x7A);
	private static final Color GOLD_HI = new Color(0xF6, 0xE4, 0xB0);
	private static final Color PARCH = new Color(0xE8, 0xD8, 0xB0);
	private static final Color MUTED = new Color(0xB9, 0xA8, 0x86);
	private static final Color FIELDBG = new Color(0x12, 0x0C, 0x0D);
	private static final Color FOCUS_RING = new Color(0xDC, 0x26, 0x26);

	private final Client client;
	private final ClientThread clientThread;
	private final LofAccounts accountStore;
	private final BufferedImage hero;
	private final BufferedImage logo;

	private String username = "";
	private String password = "";
	private boolean rememberMe = true;
	private int focus = FOCUS_USER;

	private List<LofAccounts.Account> saved = new ArrayList<>();
	private String pendUser, pendPass;

	@Inject
	private LofLoginRenderer(Client client, ClientThread clientThread, LofAccounts accountStore,
		MouseManager mouseManager, KeyManager keyManager, EventBus eventBus)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.accountStore = accountStore;
		BufferedImage h = null, l = null;
		try
		{
			h = ImageUtil.loadImageResource(LofLoginRenderer.class, "hero-bg.jpg");
			l = ImageUtil.loadImageResource(LofLoginRenderer.class, "logo.png");
		}
		catch (Exception e)
		{
			log.warn("login art missing", e);
		}
		this.hero = h;
		this.logo = l;
		reloadAccounts();
		mouseManager.registerMouseListener(this);
		keyManager.registerKeyListener(this);
		eventBus.register(this);
	}

	private void reloadAccounts()
	{
		saved = accountStore.load();
	}

	private boolean active()
	{
		return client.getGameState() == GameState.LOGIN_SCREEN;
	}

	private void doLogin()
	{
		final String u = username.trim();
		final String p = password;
		if (u.isEmpty() || p.isEmpty())
		{
			return;
		}
		if (rememberMe)
		{
			pendUser = u;
			pendPass = p;
		}
		clientThread.invokeLater(() ->
		{
			client.setUsername(u);
			client.setPassword(p);
			client.setGameState(GameState.LOGGING_IN);
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN && pendUser != null)
		{
			accountStore.remember(pendUser, pendPass);
			pendUser = pendPass = null;
			reloadAccounts();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			reloadAccounts();
		}
	}

	// ---- layout ----

	private static final class Layout
	{
		int ox, oy, px, py, pw, ph, fx, fw;
		Rectangle username, password, loginBtn, remember;
		Rectangle[] tiles; // [0..n-1] saved accounts, [n] = "+ New"
		int savedShown;
	}

	private Layout layout()
	{
		Layout l = new Layout();
		int cw = client.getCanvasWidth(), ch = client.getCanvasHeight();
		l.ox = (cw - DW) / 2;
		l.oy = Math.max(0, (ch - DH) / 2);
		l.pw = 340;
		l.ph = 222;
		l.px = l.ox + (DW - l.pw) / 2;
		l.py = l.oy + 182;
		l.fx = l.px + 28;
		l.fw = l.pw - 56;
		l.username = new Rectangle(l.fx, l.py + 42, l.fw, 30);
		l.password = new Rectangle(l.fx, l.py + 100, l.fw, 30);
		l.loginBtn = new Rectangle(l.fx, l.py + 146, l.fw, 38);
		l.remember = new Rectangle(l.fx, l.py + 190, 130, 20);

		l.savedShown = Math.min(saved.size(), MAX_VISIBLE);
		int count = l.savedShown + 1;
		int tw = 92, th = 46, gap = 12;
		int total = count * tw + (count - 1) * gap;
		int sx = l.ox + (DW - total) / 2;
		int ty = l.py + l.ph + 26;
		l.tiles = new Rectangle[count];
		for (int i = 0; i < count; i++)
		{
			l.tiles[i] = new Rectangle(sx + i * (tw + gap), ty, tw, th);
		}
		return l;
	}

	// ---- render ----

	public void render(Graphics2D g)
	{
		final GameState gs = client.getGameState();
		if (gs != GameState.LOGIN_SCREEN && gs != GameState.LOGGING_IN)
		{
			return;
		}
		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			return;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		if (hero != null)
		{
			double sc = Math.max(cw / (double) hero.getWidth(), ch / (double) hero.getHeight());
			int dw = (int) (hero.getWidth() * sc), dh = (int) (hero.getHeight() * sc);
			g.drawImage(hero, (cw - dw) / 2, (ch - dh) / 2, dw, dh, null);
		}
		else
		{
			g.setColor(new Color(0x08, 0x05, 0x06));
			g.fillRect(0, 0, cw, ch);
		}
		g.setColor(new Color(8, 5, 6, 150));
		g.fillRect(0, 0, cw, ch);
		vgrad(g, 0, 0, cw, 150, new Color(8, 5, 6, 190), new Color(8, 5, 6, 0));
		vgrad(g, 0, ch - 180, cw, 180, new Color(8, 5, 6, 0), new Color(8, 5, 6, 235));
		// Radius must reach the bottom of the canvas so the glow fades out
		// smoothly instead of ending in a hard edge partway down.
		g.setPaint(new RadialGradientPaint(new Point(cw / 2, 40), Math.max(360, Math.max(cw / 2, ch)),
			new float[]{0f, 1f},
			new Color[]{new Color(0xDC, 0x26, 0x26, 70), new Color(0xDC, 0x26, 0x26, 0)}));
		g.fillRect(0, 0, cw, ch);

		final Layout l = layout();

		if (logo != null)
		{
			int lw = 300, lh = (int) (logo.getHeight() * (lw / (double) logo.getWidth()));
			g.drawImage(logo, l.ox + (DW - lw) / 2, l.oy + 40, lw, lh, null);
		}

		// While connecting, cover the vanilla OSRS "logging in" screen with our branding.
		if (gs == GameState.LOGGING_IN)
		{
			panel(g, l.px, l.py, l.pw, 116);
			g.setFont(new Font("Serif", Font.BOLD, 24));
			centered(g, "Logging in…", l.ox + DW / 2, l.py + 58, GOLD);
			g.setFont(new Font("SansSerif", Font.PLAIN, 12));
			centered(g, "Entering the war for Varrock", l.ox + DW / 2, l.py + 82, MUTED);
			return;
		}

		panel(g, l.px, l.py, l.pw, l.ph);

		label(g, "USERNAME", l.fx, l.py + 34);
		rightText(g, "Register?", l.px + l.pw - 28, l.py + 34);
		field(g, l.username, username, false, focus == FOCUS_USER);

		label(g, "PASSWORD", l.fx, l.py + 92);
		rightText(g, "Forgot?", l.px + l.pw - 28, l.py + 92);
		field(g, l.password, password, true, focus == FOCUS_PASS);

		goldButton(g, l.loginBtn, "LOG IN");

		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1f));
		g.drawRect(l.fx, l.py + 194, 12, 12);
		if (rememberMe)
		{
			g.setColor(GOLD_HI);
			g.setFont(new Font("SansSerif", Font.BOLD, 11));
			g.drawString("✓", l.fx + 2, l.py + 204);
		}
		g.setColor(PARCH);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		g.drawString("Remember me", l.fx + 20, l.py + 204);

		int sy = l.py + l.ph + 16;
		g.setFont(new Font("SansSerif", Font.BOLD, 11));
		centered(g, "SAVED ACCOUNTS", l.ox + DW / 2, sy, GOLD);
		for (int i = 0; i < l.tiles.length; i++)
		{
			boolean isNew = (i == l.savedShown);
			String name = isNew ? "New" : saved.get(i).username;
			tile(g, l.tiles[i], name, isNew);
		}
	}

	// ---- mouse ----

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (!active())
		{
			return e;
		}
		final Layout l = layout();
		final Point p = e.getPoint();
		if (l.username.contains(p))
		{
			focus = FOCUS_USER;
			e.consume();
		}
		else if (l.password.contains(p))
		{
			focus = FOCUS_PASS;
			e.consume();
		}
		else if (l.remember.contains(p))
		{
			rememberMe = !rememberMe;
			e.consume();
		}
		else if (l.loginBtn.contains(p))
		{
			doLogin();
			e.consume();
		}
		else
		{
			for (int i = 0; i < l.tiles.length; i++)
			{
				if (!l.tiles[i].contains(p))
				{
					continue;
				}
				if (i == l.savedShown)
				{
					// "+ New" — clear for a fresh login
					username = "";
					password = "";
					focus = FOCUS_USER;
				}
				else
				{
					LofAccounts.Account a = saved.get(i);
					username = a.username;
					password = a.password;
					doLogin();
				}
				e.consume();
				return e;
			}
			Rectangle panel = new Rectangle(l.px, l.oy, l.pw, DH);
			if (panel.contains(p))
			{
				focus = FOCUS_NONE;
				e.consume();
			}
		}
		return e;
	}

	@Override public MouseEvent mouseClicked(MouseEvent e) { return maybeSwallow(e); }
	@Override public MouseEvent mouseReleased(MouseEvent e) { return maybeSwallow(e); }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return e; }

	private MouseEvent maybeSwallow(MouseEvent e)
	{
		if (active())
		{
			Layout l = layout();
			Rectangle panel = new Rectangle(l.px, l.oy, l.pw, DH);
			boolean onTile = false;
			for (Rectangle t : l.tiles)
			{
				if (t.contains(e.getPoint()))
				{
					onTile = true;
					break;
				}
			}
			if (panel.contains(e.getPoint()) || onTile)
			{
				e.consume();
			}
		}
		return e;
	}

	// ---- keyboard ----

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!active() || focus == FOCUS_NONE)
		{
			return;
		}
		char c = e.getKeyChar();
		if (c >= ' ' && c != 127)
		{
			if (focus == FOCUS_USER && username.length() < MAX_LEN)
			{
				username += c;
			}
			else if (focus == FOCUS_PASS && password.length() < MAX_LEN)
			{
				password += c;
			}
			e.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!active())
		{
			return;
		}
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_BACK_SPACE:
				if (focus == FOCUS_USER && !username.isEmpty())
				{
					username = username.substring(0, username.length() - 1);
				}
				else if (focus == FOCUS_PASS && !password.isEmpty())
				{
					password = password.substring(0, password.length() - 1);
				}
				e.consume();
				break;
			case KeyEvent.VK_TAB:
				focus = (focus == FOCUS_USER) ? FOCUS_PASS : FOCUS_USER;
				e.consume();
				break;
			case KeyEvent.VK_ENTER:
				doLogin();
				e.consume();
				break;
			default:
				if (focus != FOCUS_NONE)
				{
					e.consume();
				}
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (active() && focus != FOCUS_NONE)
		{
			e.consume();
		}
	}

	@Override
	public boolean isEnabledOnLoginScreen()
	{
		return true;
	}

	@Override
	public void focusLost()
	{
	}

	// ---- drawing helpers ----

	private static void panel(Graphics2D g, int x, int y, int w, int h)
	{
		g.setColor(new Color(10, 7, 8, 235));
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 16, 16));
		g.setStroke(new BasicStroke(2f));
		g.setColor(new Color(0x5a, 0x2a, 0x2a));
		g.draw(new RoundRectangle2D.Float(x + 1, y + 1, w - 2, h - 2, 15, 15));
		g.setColor(new Color(0xDC, 0x26, 0x26, 90));
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Float(x + 3, y + 3, w - 6, h - 6, 13, 13));
	}

	private static void label(Graphics2D g, String s, int x, int y)
	{
		g.setFont(new Font("SansSerif", Font.BOLD, 12));
		g.setColor(GOLD);
		g.drawString(s, x, y);
	}

	private static void rightText(Graphics2D g, String s, int rightX, int y)
	{
		g.setFont(new Font("SansSerif", Font.PLAIN, 11));
		g.setColor(MUTED);
		g.drawString(s, rightX - g.getFontMetrics().stringWidth(s), y);
	}

	private static void field(Graphics2D g, Rectangle r, String text, boolean mask, boolean focused)
	{
		g.setColor(FIELDBG);
		g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));
		g.setStroke(new BasicStroke(focused ? 1.6f : 1f));
		g.setColor(focused ? FOCUS_RING : new Color(0x3a, 0x30, 0x22));
		g.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));

		g.setFont(new Font("SansSerif", Font.PLAIN, 14));
		String shown = mask ? repeat('•', text.length()) : text;
		int tx = r.x + 10;
		if (!shown.isEmpty())
		{
			g.setColor(PARCH);
			g.drawString(shown, tx, r.y + 20);
			tx += g.getFontMetrics().stringWidth(shown);
		}
		if (focused)
		{
			g.setColor(GOLD_HI);
			g.fillRect(tx + 1, r.y + 7, 1, r.height - 14);
		}
	}

	private static void goldButton(Graphics2D g, Rectangle r, String s)
	{
		g.setPaint(new GradientPaint(0, r.y, GOLD_HI, 0, r.y + r.height, new Color(0xC8, 0x9A, 0x3C)));
		g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
		g.setColor(new Color(0x6b, 0x4a, 0x12));
		g.setStroke(new BasicStroke(1.5f));
		g.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
		g.setFont(new Font("Serif", Font.BOLD, 20));
		int tw = g.getFontMetrics().stringWidth(s);
		g.setColor(new Color(0x2a, 0x1c, 0x06));
		g.drawString(s, r.x + (r.width - tw) / 2, r.y + r.height / 2 + 7);
	}

	private static void tile(Graphics2D g, Rectangle r, String name, boolean isNew)
	{
		g.setColor(new Color(12, 8, 9, 220));
		g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
		g.setColor(isNew ? new Color(0x3a, 0x30, 0x22) : new Color(0x5a, 0x2a, 0x2a));
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
		g.setColor(isNew ? new Color(0x2a, 0x24, 0x18) : new Color(0x3a, 0x1a, 0x1a));
		g.fillOval(r.x + 8, r.y + 10, 26, 26);
		g.setColor(isNew ? GOLD : PARCH);
		g.setFont(new Font("SansSerif", Font.BOLD, isNew ? 18 : 14));
		String ic = isNew ? "+" : name.substring(0, 1).toUpperCase();
		g.drawString(ic, r.x + 21 - g.getFontMetrics().stringWidth(ic) / 2, r.y + 28);
		g.setColor(PARCH);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		String shown = isNew ? "New" : clip(g, name, r.width - 46);
		g.drawString(shown, r.x + 42, r.y + 28);
	}

	private static String clip(Graphics2D g, String s, int maxW)
	{
		if (g.getFontMetrics().stringWidth(s) <= maxW)
		{
			return s;
		}
		while (s.length() > 1 && g.getFontMetrics().stringWidth(s + "…") > maxW)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	private static void centered(Graphics2D g, String s, int cx, int by, Color c)
	{
		g.setColor(c);
		g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, by);
	}

	private static void vgrad(Graphics2D g, int x, int y, int w, int h, Color top, Color bot)
	{
		g.setPaint(new GradientPaint(0, y, top, 0, y + h, bot));
		g.fillRect(x, y, w, h);
	}

	private static String repeat(char c, int n)
	{
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++)
		{
			sb.append(c);
		}
		return sb.toString();
	}
}

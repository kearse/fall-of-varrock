/*
 * Fall of Varrock — the lof modal windows are EXCLUSIVE: opening one closes the others.
 *
 * Every framed lof modal draws dead-centre on ALWAYS_ON_TOP and its mouse listener consumes
 * clicks over that region, so a window left open (they only closed via the ✕) silently sat on
 * top of the next window — or on top of the varp-driven kit editor — and swallowed its clicks
 * ("buttons don't work") while the stack looked mis-placed. Overlays register here; plugins call
 * openExclusive() before showing, and forward varp signals so foreign varp-driven windows
 * (the kit editor, the duel-rules flow) also dismiss the registered stack.
 *
 * Not a plugin — a static utility like LofTheme.
 */
package net.runelite.client.plugins.loftheme;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LofWindows
{
	/** A registered framed modal: visible-state + a way to hide it. */
	public interface Window
	{
		boolean isWindowVisible();

		void hideWindow();
	}

	/** Foreign varp-driven windows that should also clear the registered stack when they open. */
	private static final int KIT_EDITOR_CONTROL_VARP = 4640;
	private static final int DUEL_RULES_VARP = 4630;

	private static final List<Window> WINDOWS = new CopyOnWriteArrayList<>();

	private LofWindows()
	{
	}

	public static void register(Window w)
	{
		if (!WINDOWS.contains(w))
		{
			WINDOWS.add(w);
		}
	}

	public static void unregister(Window w)
	{
		WINDOWS.remove(w);
	}

	/** Hide every registered window except [opening] — call just before showing a window. */
	public static void openExclusive(Window opening)
	{
		for (Window w : WINDOWS)
		{
			if (w != opening && w.isWindowVisible())
			{
				w.hideWindow();
			}
		}
	}

	/** Forward every VarbitChanged here (cheap set check): a foreign modal opening clears the stack. */
	public static void onForeignSignal(int varpId, int value)
	{
		if (value == 0 || (varpId != KIT_EDITOR_CONTROL_VARP && varpId != DUEL_RULES_VARP))
		{
			return;
		}
		for (Window w : WINDOWS)
		{
			if (w.isWindowVisible())
			{
				w.hideWindow();
			}
		}
	}
}

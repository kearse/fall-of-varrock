/*
 * Fall of Varrock — Companion Sparring settings overlay (mouse input).
 *
 * Hit-tests left-clicks against the open settings window: rows toggle rules / pick radios,
 * Load Last / Start Bout / Decline send their actions, and any click on the window is consumed so
 * it doesn't fall through to the game world. All actions go back as "::lofspar <action>".
 */
package net.runelite.client.plugins.lofspar;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofSparMouseListener extends MouseAdapter
{
	private final LofSparPlugin plugin;
	private final LofSparOverlay overlay;

	@Inject
	LofSparMouseListener(LofSparPlugin plugin, LofSparOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!overlay.isShowing() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofSparOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofSparOverlay.START)
		{
			plugin.sendAction("next"); // settings "Continue" → the kit-locker step
		}
		else if (hit == LofSparOverlay.ACCEPT)
		{
			plugin.sendAction("accept"); // confirm overview → begin the bout
		}
		else if (hit == LofSparOverlay.BACK)
		{
			plugin.sendAction("back"); // confirm overview → back to settings
		}
		else if (hit == LofSparOverlay.DECLINE)
		{
			plugin.sendAction("x");
		}
		else if (hit == LofSparOverlay.LOAD)
		{
			plugin.sendAction("load");
		}
		else if (hit >= LofSparOverlay.RULE_BASE)
		{
			plugin.sendAction("t" + (hit - LofSparOverlay.RULE_BASE));
		}
		else if (hit >= LofSparOverlay.KIT_BASE)
		{
			plugin.sendAction("m" + (hit - LofSparOverlay.KIT_BASE));
		}
		else if (hit >= LofSparOverlay.DIFF_BASE)
		{
			plugin.sendAction("d" + (hit - LofSparOverlay.DIFF_BASE));
		}
		else if (hit >= LofSparOverlay.STYLE_BASE)
		{
			plugin.sendAction("f" + (hit - LofSparOverlay.STYLE_BASE));
		}

		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	private MouseEvent swallowIfOnWindow(MouseEvent event)
	{
		// mousePressed() consumes only left-button presses, so only left releases/clicks may be
		// swallowed here: if the game saw a middle/right press but never its release, the engine's
		// camera drag stays live and the view keeps rotating with the mouse until the next click.
		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofSparOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

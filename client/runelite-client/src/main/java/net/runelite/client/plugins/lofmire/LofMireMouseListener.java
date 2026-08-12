/*
 * Fall of Varrock — Mire Dispenser window (mouse input).
 *
 * Hit-tests left-clicks against the open window: the footer button sends attune/claim (the window
 * stays open — the server re-pulses the varp and the counters update in place), the X closes.
 * Clicks anywhere on the window are consumed so they never fall through to the game world.
 */
package net.runelite.client.plugins.lofmire;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofMireMouseListener extends MouseAdapter
{
	private final LofMirePlugin plugin;
	private final LofMireOverlay overlay;

	@Inject
	LofMireMouseListener(LofMirePlugin plugin, LofMireOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!overlay.isVisible() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofMireOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofMireOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofMireOverlay.ACTION)
		{
			plugin.sendMire(overlay.isAttuned() ? "claim" : "attune");
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofMireOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

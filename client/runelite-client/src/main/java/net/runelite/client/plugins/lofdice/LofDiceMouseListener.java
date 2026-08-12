/*
 * Fall of Varrock — the Gambler's Table (mouse input).
 */
package net.runelite.client.plugins.lofdice;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofDiceMouseListener extends MouseAdapter
{
	private final LofDicePlugin plugin;
	private final LofDiceOverlay overlay;

	@Inject
	LofDiceMouseListener(LofDicePlugin plugin, LofDiceOverlay overlay)
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
		if (hit == LofDiceOverlay.OUTSIDE)
		{
			return event;
		}
		if (hit == LofDiceOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofDiceOverlay.ROLL)
		{
			overlay.onRollSent();
			plugin.sendRoll(overlay.getStake());
		}
		else if (hit >= LofDiceOverlay.CHIP_BASE)
		{
			overlay.applyChip(hit - LofDiceOverlay.CHIP_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofDiceOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

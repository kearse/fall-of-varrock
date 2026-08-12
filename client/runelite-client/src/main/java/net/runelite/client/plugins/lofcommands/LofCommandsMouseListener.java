/*
 * Fall of Varrock — command window (mouse input).
 *
 * Hit-tests left-clicks against the open window: tabs switch rank, a row inserts its "::cmd " into
 * the chat input (so you can add arguments), the X closes it. Any click on the window is consumed
 * so it doesn't fall through to the game world; a click outside the window closes it.
 */
package net.runelite.client.plugins.lofcommands;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofCommandsMouseListener extends MouseAdapter
{
	private final LofCommandsPlugin plugin;
	private final LofCommandsOverlay overlay;

	@Inject
	LofCommandsMouseListener(LofCommandsPlugin plugin, LofCommandsOverlay overlay)
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
		if (hit == LofCommandsOverlay.OUTSIDE)
		{
			// Click-away closes the window, and the click still reaches the game.
			plugin.close();
			return event;
		}

		if (hit == LofCommandsOverlay.CLOSE)
		{
			plugin.close();
		}
		else if (hit >= LofCommandsOverlay.ROW_BASE)
		{
			final String cmd = overlay.commandAt(hit - LofCommandsOverlay.ROW_BASE);
			if (cmd != null)
			{
				plugin.insertCommand(cmd);
				plugin.close();
			}
		}
		else if (hit >= LofCommandsOverlay.TAB_BASE)
		{
			overlay.setActiveTab(hit - LofCommandsOverlay.TAB_BASE);
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofCommandsOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

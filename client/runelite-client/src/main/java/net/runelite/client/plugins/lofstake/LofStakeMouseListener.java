/*
 * Fall of Varrock — Duel Arena stake overlay (mouse input).
 *
 * Hit-tests left-clicks against the open stake window: clicking one of YOUR staked items un-stakes
 * it, Accept/Decline send the matching action, and any click on the window is consumed so it doesn't
 * fall through to the game world. Adding items is NOT handled here — that stays native (click the
 * real inventory, which the window never covers). All actions go back as "::stake <action> [slot]".
 */
package net.runelite.client.plugins.lofstake;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofStakeMouseListener extends MouseAdapter
{
	private final LofStakePlugin plugin;
	private final LofStakeOverlay overlay;

	@Inject
	LofStakeMouseListener(LofStakePlugin plugin, LofStakeOverlay overlay)
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
		if (hit == LofStakeOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window (e.g. the inventory) reach the game
		}

		if (hit == LofStakeOverlay.ACCEPT)
		{
			plugin.sendAction("a");
		}
		else if (hit == LofStakeOverlay.DECLINE)
		{
			plugin.sendAction("d");
		}
		else if (hit >= LofStakeOverlay.SLOT_BASE)
		{
			plugin.sendAction("rm " + (hit - LofStakeOverlay.SLOT_BASE));
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
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofStakeOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

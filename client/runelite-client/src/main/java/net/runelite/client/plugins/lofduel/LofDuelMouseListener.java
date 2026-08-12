/*
 * Fall of Varrock — Duel Arena rules + confirmation overlays (mouse input).
 *
 * Hit-tests left-clicks against whichever duel window is open: on the RULES screen a chip toggles
 * a rule and preset/Accept/Decline send the matching action; on the CONFIRMATION screen Accept
 * (refused while the change lockout shows "Wait…") and Decline route through the SECURE
 * TradeSession as stake actions. Any click on an open window is consumed so it doesn't fall
 * through to the game world.
 */
package net.runelite.client.plugins.lofduel;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofDuelMouseListener extends MouseAdapter
{
	private final LofDuelPlugin plugin;
	private final LofDuelOverlay overlay;
	private final LofDuelConfirmOverlay confirmOverlay;

	@Inject
	LofDuelMouseListener(LofDuelPlugin plugin, LofDuelOverlay overlay, LofDuelConfirmOverlay confirmOverlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
		this.confirmOverlay = confirmOverlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		// Confirmation screen (varp-gated; never open at the same time as the rules screen).
		if (confirmOverlay.isShowing())
		{
			final int hit = confirmOverlay.hitTest(event.getPoint());
			if (hit == LofDuelConfirmOverlay.OUTSIDE)
			{
				return event;
			}
			if (hit == LofDuelConfirmOverlay.ACCEPT && !confirmOverlay.isAcceptLocked())
			{
				plugin.sendStakeAction("a");
			}
			else if (hit == LofDuelConfirmOverlay.DECLINE)
			{
				plugin.sendStakeAction("d");
			}
			event.consume();
			return event;
		}

		if (!overlay.isShowing())
		{
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofDuelOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofDuelOverlay.ACCEPT)
		{
			// Swallowed during the post-change "Wait…" window (server enforces the same lockout).
			if (!overlay.isAcceptWaiting())
			{
				plugin.sendAction("a");
			}
		}
		else if (hit == LofDuelOverlay.DECLINE)
		{
			plugin.sendAction("d");
		}
		else if (hit == LofDuelOverlay.LOAD)
		{
			plugin.sendAction("load");
		}
		else if (hit == LofDuelOverlay.SAVE)
		{
			plugin.sendAction("save");
		}
		else if (hit == LofDuelOverlay.LOAD_SAVED)
		{
			plugin.sendAction("loadsaved");
		}
		else if (hit == LofDuelOverlay.WHIP)
		{
			plugin.sendAction("whip");
		}
		else if (hit == LofDuelOverlay.BOXING)
		{
			plugin.sendAction("box");
		}
		else if (hit >= LofDuelOverlay.SLOT_BASE)
		{
			plugin.sendAction("s" + (hit - LofDuelOverlay.SLOT_BASE));
		}
		else if (hit >= LofDuelOverlay.RULE_BASE)
		{
			plugin.sendAction("t" + (hit - LofDuelOverlay.RULE_BASE));
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
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofDuelOverlay.OUTSIDE)
		{
			event.consume();
		}
		else if (confirmOverlay.isShowing() && confirmOverlay.hitTest(event.getPoint()) != LofDuelConfirmOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

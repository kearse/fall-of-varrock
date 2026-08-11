/*
 * Fall of Varrock — Duel Arena rules overlay (mouse input).
 *
 * Hit-tests left-clicks against the open rules window: a chip toggles a rule, Accept/Decline send
 * the matching action, and any click on the window is consumed so it doesn't fall through to the
 * game world. All actions go back to the server as "::duel <action>".
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

	@Inject
	LofDuelMouseListener(LofDuelPlugin plugin, LofDuelOverlay overlay)
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
		if (hit == LofDuelOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofDuelOverlay.ACCEPT)
		{
			plugin.sendAction("a");
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
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofDuelOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

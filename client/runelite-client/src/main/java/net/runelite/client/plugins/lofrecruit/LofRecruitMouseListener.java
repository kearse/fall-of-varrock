/*
 * Fall of Varrock — Muster Companions window (mouse input).
 *
 * Hit-tests left-clicks against the open window: a discipline card sends the muster (the window
 * stays open — the server re-pulses the varp and the banner strip updates), the X closes. Clicks
 * anywhere on the window are consumed so they never fall through to the game world.
 */
package net.runelite.client.plugins.lofrecruit;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofRecruitMouseListener extends MouseAdapter
{
	private final LofRecruitPlugin plugin;
	private final LofRecruitOverlay overlay;

	@Inject
	LofRecruitMouseListener(LofRecruitPlugin plugin, LofRecruitOverlay overlay)
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
		if (hit == LofRecruitOverlay.OUTSIDE)
		{
			return event; // let clicks outside the window reach the game
		}

		if (hit == LofRecruitOverlay.CLOSE)
		{
			overlay.setVisible(false);
		}
		else if (hit == LofRecruitOverlay.REGALIA_TAB)
		{
			plugin.sendRegalia();
			overlay.setVisible(false); // the native shop opens over the world
		}
		else if (hit >= LofRecruitOverlay.CARD_BASE)
		{
			final int style = hit - LofRecruitOverlay.CARD_BASE;
			if (style >= 0 && style < LofRecruitOverlay.STYLE_TOKENS.length)
			{
				plugin.sendRecruit(LofRecruitOverlay.STYLE_TOKENS[style]);
			}
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
		if (overlay.isVisible() && overlay.hitTest(event.getPoint()) != LofRecruitOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}

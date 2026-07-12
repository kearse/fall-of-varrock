/*
 * Fall of Varrock — Quest Journal.
 *
 * A Quest-Helper-style guide for Fall of Varrock's custom quest line: a sidebar journal listing
 * every custom quest (done, in flight, and what's ahead), each with its "why", its step checklist
 * and what it unlocks; plus client-drawn guidance arrows (scene + minimap) for the quest the
 * player chooses to track. Tracking is the player's choice — untrack everything (or hit the
 * free-play toggle, which also mutes the SERVER's hint arrows via ::questguide) to roam free.
 *
 * State comes from the server's QuestJournalPlugin varps (see LofQuestVarps — no custom packets),
 * so the journal stays live as quests advance. Arrow drawing is modelled on the RuneLite Quest
 * Helper plugin (BSD-2, Zoinkwiz) — see LofQuestsWorldOverlay/LofQuestsMinimapOverlay.
 */
package net.runelite.client.plugins.lofquests;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Lof Quest Journal",
	description = "Fall of Varrock's quest guide: steps, unlocks, and guidance arrows you can turn off.",
	tags = {"lof", "quest", "journal", "helper", "guide", "arrow"},
	enabledByDefault = true
)
public class LofQuestsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofQuestsWorldOverlay worldOverlay;

	@Inject
	private LofQuestsMinimapOverlay minimapOverlay;

	@Inject
	private LofQuestsConfig config;

	private LofQuestsPanel panel;
	private NavigationButton navButton;

	/** The quest the player chose to guide toward (null = nothing tracked). */
	@Getter
	private LofQuest trackedQuest;

	/** True once the player explicitly untracked, so auto-track stops re-selecting. */
	private boolean playerUntracked;

	@Provides
	LofQuestsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofQuestsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(worldOverlay);
		overlayManager.add(minimapOverlay);

		panel = new LofQuestsPanel(this, client);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Quest Journal")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refresh();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(worldOverlay);
		overlayManager.remove(minimapOverlay);
		clientToolbar.removeNavigation(navButton);
		trackedQuest = null;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();
		if (varp == LofQuestVarps.RECRUIT || varp == LofQuestVarps.WARPREP || varp == LofQuestVarps.GUIDE_MUTED)
		{
			refresh();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refresh();
		}
	}

	/** A quest we can point an arrow at — in progress, or not started yet (the intro's first step). */
	private static boolean guidable(LofQuestState state)
	{
		return state == LofQuestState.IN_PROGRESS || state == LofQuestState.NOT_STARTED;
	}

	/** Re-derive tracking and repaint the journal (safe to call from the client thread). */
	private void refresh()
	{
		// Drop the tracker once its quest is finished/locked; then auto-track picks the next one up.
		if (trackedQuest != null && !guidable(trackedQuest.state(client)))
		{
			trackedQuest = null;
			playerUntracked = false;
		}
		// Auto-guide by default (the server no longer draws arrows): follow the active quest, or the
		// next not-started one so a fresh recruit is guided from the very first objective.
		if (trackedQuest == null && !playerUntracked && config.autoTrack())
		{
			LofQuest inProgress = null;
			LofQuest notStarted = null;
			for (LofQuest quest : LofQuest.values())
			{
				LofQuestState state = quest.state(client);
				if (state == LofQuestState.IN_PROGRESS && inProgress == null)
				{
					inProgress = quest;
				}
				else if (state == LofQuestState.NOT_STARTED && notStarted == null)
				{
					notStarted = quest;
				}
			}
			trackedQuest = inProgress != null ? inProgress : notStarted;
		}
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	/** Panel: the player picked a quest to guide (null = stop guiding). */
	void setTrackedQuest(LofQuest quest)
	{
		trackedQuest = quest;
		playerUntracked = quest == null;
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	/** Panel: flip the SERVER's guidance arrows (free play) — same as typing ::questguide. */
	void toggleServerGuidance()
	{
		clientThread.invokeLater(() -> client.runScript(ScriptID.CHAT_SEND, "::questguide", 0, 0, 0, -1));
	}

	/** The world tile the overlays should point at right now (null = nothing to draw). */
	WorldPoint activeTarget()
	{
		if (trackedQuest == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		// Guidance muted (free play, ::questguide / the Journal toggle) — draw no arrows.
		if (LofQuestVarps.guideMuted(client))
		{
			return null;
		}
		return trackedQuest.currentTarget(client);
	}
}

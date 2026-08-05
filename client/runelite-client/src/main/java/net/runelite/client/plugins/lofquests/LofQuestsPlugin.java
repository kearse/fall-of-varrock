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
import java.util.function.Function;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loftheme.LofWindows;
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

	@Inject
	private NpcOverlayService npcOverlayService;

	@Inject
	private LofQuestBookOverlay questBook;

	@Inject
	private MouseManager mouseManager;

	private LofQuestsPanel panel;
	private NavigationButton navButton;
	private NavigationButton bookButton;
	private LofQuestBookMouseListener questBookMouse;
	private MouseWheelListener questBookWheel;

	/** Highlighter registered with the shared NPC-overlay service; kept so we can unregister it. */
	private final Function<NPC, HighlightedNpc> objectiveHighlighter = this::highlightObjectiveNpc;

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

		// The floating Quest Journal window (Feudal-Ranks-style): a client-drawn overlay opened by
		// the server's QUEST_BOOK_OPEN varp, a toolbar button, or the ::quests command.
		overlayManager.add(questBook);
		LofWindows.register(questBook);
		questBookMouse = new LofQuestBookMouseListener(this, questBook);
		mouseManager.registerMouseListener(questBookMouse);
		questBookWheel = event ->
		{
			if (questBook.handleScroll(event.getPoint(), event.getWheelRotation()))
			{
				event.consume();
			}
			return event;
		};
		mouseManager.registerMouseWheelListener(questBookWheel);
		bookButton = NavigationButton.builder()
			.tooltip("Quest Journal (window)")
			.icon(icon)
			.priority(7)
			.onClick(() -> clientThread.invokeLater(() -> openQuestBook(activeChainQuest())))
			.build();
		clientToolbar.addNavigation(bookButton);

		npcOverlayService.registerHighlighter(objectiveHighlighter);

		refresh();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(worldOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(questBook);
		LofWindows.unregister(questBook);
		if (questBookMouse != null)
		{
			mouseManager.unregisterMouseListener(questBookMouse);
			questBookMouse = null;
		}
		if (questBookWheel != null)
		{
			mouseManager.unregisterMouseWheelListener(questBookWheel);
			questBookWheel = null;
		}
		questBook.setVisible(false);
		npcOverlayService.unregisterHighlighter(objectiveHighlighter);
		clientToolbar.removeNavigation(navButton);
		clientToolbar.removeNavigation(bookButton);
		trackedQuest = null;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();

		// Server signal to open the floating Quest Journal window, focused on a quest (value = its
		// chain index + 1). The pulse's falling edge (0) never closes the window.
		if (varp == LofQuestVarps.QUEST_BOOK_OPEN)
		{
			final int value = client.getVarpValue(LofQuestVarps.QUEST_BOOK_OPEN);
			if (value != 0)
			{
				openQuestBook(LofQuest.byChainIndex(value - 1));
			}
			return;
		}

		if (varp == LofQuestVarps.RECRUIT || varp == LofQuestVarps.WARPREP || varp == LofQuestVarps.GUIDE_MUTED
			|| varp == LofQuestVarps.ROGUE_PROBLEM || varp == LofQuestVarps.KNIGHTS || varp == LofQuestVarps.RANGED
			|| varp == LofQuestVarps.SURVIVAL || varp == LofQuestVarps.CONQUEST)
		{
			refresh();
		}
	}

	// --- floating Quest Journal window control ---

	/** The quest the window defaults to: the first in-progress chain quest, else the first
	 *  unfinished one, else the last (a fully-completed chain focuses King). */
	private LofQuest activeChainQuest()
	{
		LofQuest firstUnfinished = null;
		for (LofQuest q : LofQuest.CHAIN)
		{
			final LofQuestState st = q.state(client);
			if (st == LofQuestState.IN_PROGRESS)
			{
				return q;
			}
			if (firstUnfinished == null && st != LofQuestState.FINISHED)
			{
				firstUnfinished = q;
			}
		}
		return firstUnfinished != null ? firstUnfinished : LofQuest.CHAIN.get(LofQuest.CHAIN.size() - 1);
	}

	/** Open the window focused on [q] (null falls back to the active quest). No-op when the player
	 *  has switched the window off in config — the server's chat status line is the fallback. */
	void openQuestBook(LofQuest q)
	{
		if (!config.questWindow())
		{
			return;
		}
		final LofQuest focus = q != null ? q : activeChainQuest();
		questBook.setFocus(focus);
		questBook.setTrackedOn(trackedQuest == focus);
		LofWindows.openExclusive(questBook);
		questBook.setVisible(true);
	}

	/** Track node clicked — switch the focused quest. */
	void focusQuestBook(int chainIndex)
	{
		final LofQuest q = LofQuest.byChainIndex(chainIndex);
		if (q == null)
		{
			return;
		}
		questBook.setFocus(q);
		questBook.setTrackedOn(trackedQuest == q);
	}

	/** Track button clicked — toggle the guidance arrow for the focused quest. */
	void toggleQuestBookTrack()
	{
		final LofQuest q = questBook.getFocus();
		if (q == null || !questBook.isTrackable())
		{
			return;
		}
		setTrackedQuest(trackedQuest == q ? null : q);
		questBook.setTrackedOn(trackedQuest == q);
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
		// The arrow only leads you TO the objective. Once a highlighted target creature is in sight
		// the tile highlight/minimap dot is guidance enough, so the arrow (and its tile marker) get out of the
		// way — mirroring RuneLite Quest Helper, which drops the arrow once the NPC is on screen. Only
		// applies while the creature highlight is actually on to hand off to.
		if (config.highlightObjectiveNpcs() && objectiveNpcInSight())
		{
			return null;
		}
		return trackedQuest.currentTarget(client);
	}

	/** Tiles from the player within which a highlighted target counts as "in sight" (≈ the viewport),
	 *  at which point the arrow hands off to the on-creature highlight. */
	private static final int IN_SIGHT_RADIUS = 15;

	/**
	 * True when at least one of the tracked step's highlighted target creatures is close enough to see
	 * (same plane, within {@link #IN_SIGHT_RADIUS}). Distance is Chebyshev via {@link WorldPoint#distanceTo}
	 * — off-plane targets read as {@code Integer.MAX_VALUE} and never count.
	 */
	private boolean objectiveNpcInSight()
	{
		LofQuest quest = trackedQuest;
		if (quest == null)
		{
			return false;
		}
		int[] ids = quest.currentHighlightNpcIds(client);
		if (ids.length == 0)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}
		WorldPoint playerLoc = local.getWorldLocation();
		if (playerLoc == null)
		{
			return false;
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || !contains(ids, npc.getId()))
			{
				continue;
			}
			WorldPoint npcLoc = npc.getWorldLocation();
			if (npcLoc != null && npcLoc.distanceTo(playerLoc) <= IN_SIGHT_RADIUS)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Highlighter for the shared NPC-overlay service. Returns a highlight for any NPC a quest could
	 * ever flag as a target (a stable membership gate — the rat is captured on spawn regardless of
	 * quest state); the live show/hide is deferred to the {@link #highlightsNpc} render predicate so
	 * the tile highlight appears only while that objective is actually active.
	 */
	private HighlightedNpc highlightObjectiveNpc(NPC npc)
	{
		if (!LofQuest.isObjectiveNpc(npc.getId()))
		{
			return null;
		}
		return HighlightedNpc.builder()
			.npc(npc)
			.highlightColor(config.arrowColor())
			.tile(true)
			.render(n -> highlightsNpc(n.getId()))
			.build();
	}

	/**
	 * Whether the tracked quest wants [npcId] highlighted right now. Same gating as the guidance
	 * arrow (logged in, a quest is tracked, not muted for free play) plus the feature's own toggle.
	 */
	private boolean highlightsNpc(int npcId)
	{
		if (!config.highlightObjectiveNpcs()
			|| client.getGameState() != GameState.LOGGED_IN
			|| LofQuestVarps.guideMuted(client))
		{
			return false;
		}
		LofQuest quest = trackedQuest;
		if (quest == null)
		{
			return false;
		}
		return contains(quest.currentHighlightNpcIds(client), npcId);
	}
}

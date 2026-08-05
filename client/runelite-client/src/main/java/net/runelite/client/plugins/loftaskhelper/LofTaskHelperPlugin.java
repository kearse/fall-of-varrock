/*
 * Fall of Varrock — Task Helper.
 *
 * A Quest-Helper-style guide for Vannaka's combat contracts (the Slayer tasks): guidance arrows in
 * the scene and on the minimap point the way to the active contract's hunting ground, and the
 * assigned monsters' tiles are highlighted (scene + minimap dot) once you're among them — the same
 * hand-off the Quest Journal does for the castle rats. The arrows use their own colour (cyan by
 * default) so they can't be mistaken for the gold Quest Journal arrows, and the whole thing is an
 * ordinary plugin — toggle it off in the plugin list to hunt unaided.
 *
 * The sidebar tab (LofTaskHelperPanel, styled like the Quest Journal) lists Vannaka's whole
 * contract roster (LofTask): what each task is, where its monsters hunt, and its terms. Pick one
 * to track and the same arrows and highlights guide you there — useful for scouting a hunting
 * ground before taking the contract, or hunting off-contract. A tracked pick wins over the active
 * contract's arrow (it's the player's explicit choice), and the moment Vannaka signs a NEW
 * contract the pick clears so the helper snaps to the real assignment.
 *
 * Progress itself is already tracked by the Slayer dial in the war-dial row (`lofdials`, varp
 * 4616); this plugin reads the same varp to know a contract is active and to caption the arrow
 * with the kill count. The target tile arrives in varp 4638 and the target's canonical npc id in
 * varp 4639, both published by the server's SlayerHudPlugin (see Alter) — no custom packets.
 * Targets are matched by cache NAME, not id, mirroring the server's kill credit: cows alone spawn
 * under four npc ids, and every variant that counts is also the one marked.
 */
package net.runelite.client.plugins.loftaskhelper;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.function.Function;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Lof Task Helper",
	description = "A sidebar tab of Vannaka's contract roster — pick a task to be guided to it; arrows point at your active war-contract's hunting ground and the assigned monsters' tiles are highlighted.",
	tags = {"lof", "slayer", "task", "contract", "assignment", "helper", "guide", "arrow", "highlight", "panel"},
	enabledByDefault = true
)
public class LofTaskHelperPlugin extends Plugin
{
	/** Packed Slayer-task varp (SlayerHudPlugin): bits 0-11 killed, bits 12-23 total; 0 = no task. */
	static final int VARP_SLAYER = 4616;

	/** Packed hunting-ground varp (SlayerHudPlugin): bits 0-13 x, bits 14-27 z, bits 28-29 height;
	 *  0 = no task, or a task with no mapped hunting ground. */
	static final int VARP_TARGET = 4638;

	/** Canonical npc id of the active contract's target (SlayerHudPlugin); 0 = no task. The
	 *  highlight matches every npc sharing this id's cache name — the server credits kills the
	 *  same way. */
	static final int VARP_TARGET_NPC = 4639;

	/** Tiles from the player within which a highlighted target counts as "in sight" (≈ the
	 *  viewport), at which point the arrow hands off to the on-creature highlight (mirrors the
	 *  Quest Journal's rat hand-off). */
	private static final int IN_SIGHT_RADIUS = 15;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private NpcOverlayService npcOverlayService;

	@Inject
	private LofTaskHelperWorldOverlay worldOverlay;

	@Inject
	private LofTaskHelperMinimapOverlay minimapOverlay;

	@Inject
	private LofTaskHelperConfig config;

	private LofTaskHelperPanel panel;
	private NavigationButton navButton;

	/** Highlighter registered with the shared NPC-overlay service; kept so we can unregister it. */
	private final Function<NPC, HighlightedNpc> targetHighlighter = this::highlightTargetNpc;

	/** The roster task the player picked in the sidebar tab (null = follow the active contract). */
	@Getter
	private LofTask selectedTask;

	/** Last seen VARP_TARGET_NPC, so a NEW contract (value change to non-zero) can clear the
	 *  player's roster pick and snap the helper to the real assignment. */
	private int lastContractNpc;

	/** The active contract target's cache name as displayed (e.g. "Goblin"); resolved on the
	 *  client thread whenever the contract varps move, read by the Swing panel. Null = none. */
	@Getter
	private String contractDisplayName;

	@Provides
	LofTaskHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofTaskHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(worldOverlay);
		overlayManager.add(minimapOverlay);
		npcOverlayService.registerHighlighter(targetHighlighter);

		panel = new LofTaskHelperPanel(this);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Task Helper")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshPanel();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(worldOverlay);
		overlayManager.remove(minimapOverlay);
		npcOverlayService.unregisterHighlighter(targetHighlighter);
		clientToolbar.removeNavigation(navButton);
		selectedTask = null;
		contractDisplayName = null;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();
		if (varp != VARP_SLAYER && varp != VARP_TARGET && varp != VARP_TARGET_NPC)
		{
			return;
		}
		if (varp == VARP_TARGET_NPC)
		{
			int npcId = client.getVarpValue(VARP_TARGET_NPC);
			// A NEW contract (not a clear, not a re-push of the same one) overrides any roster
			// pick — Vannaka's assignment is what actually progresses, so guide there.
			if (npcId != 0 && npcId != lastContractNpc)
			{
				selectedTask = null;
			}
			lastContractNpc = npcId;
			contractDisplayName = resolveContractName(npcId);
		}
		// The highlighter's membership depends on the live contract/pick, so re-evaluate the
		// scene's npcs whenever it changes (npcs spawned after this are captured by the spawn hook).
		if (varp == VARP_SLAYER || varp == VARP_TARGET_NPC)
		{
			npcOverlayService.rebuild();
		}
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			lastContractNpc = client.getVarpValue(VARP_TARGET_NPC);
			contractDisplayName = resolveContractName(lastContractNpc);
			refreshPanel();
		}
	}

	/** Panel: the player picked a roster task to guide toward (null = stop tracking). */
	void setSelectedTask(LofTask task)
	{
		selectedTask = task;
		npcOverlayService.rebuild();
		refreshPanel();
	}

	/** Repaint the sidebar tab from live state (safe to call from any thread). */
	private void refreshPanel()
	{
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	/** The [npcId]'s cache name for display, e.g. "Goblin" (null = no contract / unresolvable).
	 *  Client-thread only — varbit/game-state events already arrive there. */
	private String resolveContractName(int npcId)
	{
		if (npcId <= 0)
		{
			return null;
		}
		NPCComposition composition = client.getNpcDefinition(npcId);
		return composition != null ? composition.getName() : null;
	}

	/** The hunting-ground tile the overlays should point at right now (null = nothing to draw). */
	WorldPoint activeTarget()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		// Once a highlighted target creature is in sight the tile marker is guidance enough, so the
		// arrow gets out of the way — mirroring the Quest Journal's rat hand-off. Only applies
		// while the creature highlight is actually on to hand off to.
		if (config.highlightTargets() && targetNpcInSight())
		{
			return null;
		}
		// A roster pick from the sidebar tab wins over the contract arrow (the player's explicit
		// choice); a new contract clears the pick (see onVarbitChanged) so this can't shadow it.
		if (selectedTask != null)
		{
			return selectedTask.getHuntingGround();
		}
		if (client.getVarpValue(VARP_SLAYER) == 0)
		{
			return null; // no active contract
		}
		int packed = client.getVarpValue(VARP_TARGET);
		if (packed == 0)
		{
			return null; // contract has no mapped hunting ground
		}
		// NOTE: no blind "hide when near the anchor tile" — the first release had one, and the
		// arrow vanishing with nothing taking over read as a bug. The arrow only steps aside for
		// the creature highlight above; with highlights off it stays up until the contract's done.
		return new WorldPoint(packed & 0x3FFF, (packed >> 14) & 0x3FFF, (packed >> 28) & 0x3);
	}

	/** The arrow's caption: the task's name, plus the kill count when it's the live contract —
	 *  e.g. "Goblins · 12/30" on contract, "Cows" on a roster pick (null = nothing to caption). */
	String progressText()
	{
		if (selectedTask != null)
		{
			// A picked task that happens to BE the contract target still shows the count.
			String count = selectedTaskIsContract() ? contractCount() : null;
			return count != null ? selectedTask.getDisplayName() + " · " + count : selectedTask.getDisplayName();
		}
		String count = contractCount();
		if (count == null)
		{
			return null;
		}
		LofTask roster = contractRosterTask();
		String name = roster != null ? roster.getDisplayName() : contractDisplayName;
		return name != null ? name + " · " + count : count;
	}

	/** The roster entry matching the active contract's target (null = no contract, or a target
	 *  the client-side roster doesn't know — the arrow still works, only the tab tag is lost). */
	LofTask contractRosterTask()
	{
		if (contractDisplayName == null)
		{
			return null;
		}
		String name = Text.standardize(contractDisplayName);
		for (LofTask task : LofTask.values())
		{
			if (Text.standardize(task.getNpcName()).equals(name))
			{
				return task;
			}
		}
		return null;
	}

	/** The active contract's kill count, e.g. "12/30" (null = no active contract). */
	String contractCount()
	{
		int packed = client.getVarpValue(VARP_SLAYER);
		if (packed == 0)
		{
			return null;
		}
		int killed = packed & 0xFFF;
		int total = (packed >> 12) & 0xFFF;
		return killed + "/" + total;
	}

	/** True when the sidebar pick and the live contract hunt the same monster. */
	boolean selectedTaskIsContract()
	{
		return selectedTask != null && selectedTask == contractRosterTask();
	}

	/**
	 * Highlighter for the shared NPC-overlay service: tile highlight + minimap dot for every npc
	 * whose cache name matches the active contract's target (or the sidebar pick). Membership is
	 * dynamic (the contract changes), so {@link #onVarbitChanged} and
	 * {@link #setSelectedTask} rebuild the service on every change; the render predicate re-checks
	 * live so a completed task blanks immediately. Note the service gives each npc at most ONE
	 * highlight (first registered highlighter wins) — so the tutorial rats, which the Quest
	 * Journal also highlights, are never double-drawn.
	 */
	private HighlightedNpc highlightTargetNpc(NPC npc)
	{
		if (!isTargetNpc(npc))
		{
			return null;
		}
		return HighlightedNpc.builder()
			.npc(npc)
			.highlightColor(config.arrowColor())
			.tile(true)
			.render(n -> config.highlightTargets() && isTargetNpc(n))
			.build();
	}

	/** True when [npc]'s cache name matches the guided target's name. */
	private boolean isTargetNpc(NPC npc)
	{
		String target = targetName();
		if (target == null)
		{
			return false;
		}
		String name = npc.getName();
		return name != null && target.equals(Text.standardize(name));
	}

	/** The guided target's standardized cache name — the sidebar pick when there is one, else the
	 *  active contract's target (null = neither / unresolvable). */
	private String targetName()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		if (selectedTask != null)
		{
			return Text.standardize(selectedTask.getNpcName());
		}
		if (client.getVarpValue(VARP_SLAYER) == 0)
		{
			return null;
		}
		int npcId = client.getVarpValue(VARP_TARGET_NPC);
		if (npcId <= 0)
		{
			return null;
		}
		NPCComposition composition = client.getNpcDefinition(npcId);
		if (composition == null || composition.getName() == null)
		{
			return null;
		}
		return Text.standardize(composition.getName());
	}

	/** True when at least one highlighted target is close enough to see (same plane, within
	 *  {@link #IN_SIGHT_RADIUS}). Distance is Chebyshev via {@link WorldPoint#distanceTo} —
	 *  off-plane targets read as {@code Integer.MAX_VALUE} and never count. */
	private boolean targetNpcInSight()
	{
		Player local = client.getLocalPlayer();
		WorldPoint playerLoc = local != null ? local.getWorldLocation() : null;
		if (playerLoc == null)
		{
			return false;
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || !isTargetNpc(npc))
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
}

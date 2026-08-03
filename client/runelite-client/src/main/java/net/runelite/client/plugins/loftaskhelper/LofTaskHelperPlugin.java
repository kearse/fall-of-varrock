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
 * Progress itself is already tracked by the Slayer dial in the war-dial row (`lofdials`, varp
 * 4616); this plugin reads the same varp to know a contract is active and to caption the arrow
 * with the kill count. The target tile arrives in varp 4638 and the target's canonical npc id in
 * varp 4639, both published by the server's SlayerHudPlugin (see Alter) — no custom packets.
 * Targets are matched by cache NAME, not id, mirroring the server's kill credit: cows alone spawn
 * under four npc ids, and every variant that counts is also the one marked.
 */
package net.runelite.client.plugins.loftaskhelper;

import com.google.inject.Provides;
import java.util.function.Function;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Lof Task Helper",
	description = "Points guidance arrows at your active war-contract's hunting ground and highlights the assigned monsters' tiles; the war-dial row tracks the kills.",
	tags = {"lof", "slayer", "task", "contract", "assignment", "helper", "guide", "arrow", "highlight"},
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
	private OverlayManager overlayManager;

	@Inject
	private NpcOverlayService npcOverlayService;

	@Inject
	private LofTaskHelperWorldOverlay worldOverlay;

	@Inject
	private LofTaskHelperMinimapOverlay minimapOverlay;

	@Inject
	private LofTaskHelperConfig config;

	/** Highlighter registered with the shared NPC-overlay service; kept so we can unregister it. */
	private final Function<NPC, HighlightedNpc> targetHighlighter = this::highlightTargetNpc;

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
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(worldOverlay);
		overlayManager.remove(minimapOverlay);
		npcOverlayService.unregisterHighlighter(targetHighlighter);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();
		// The highlighter's membership depends on the live contract, so re-evaluate the scene's
		// npcs whenever it changes (npcs spawned after this are captured by the spawn hook).
		if (varp == VARP_SLAYER || varp == VARP_TARGET_NPC)
		{
			npcOverlayService.rebuild();
		}
	}

	/** The hunting-ground tile the overlays should point at right now (null = nothing to draw). */
	WorldPoint activeTarget()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		if (client.getVarpValue(VARP_SLAYER) == 0)
		{
			return null; // no active contract
		}
		// Once a highlighted target creature is in sight the tile marker is guidance enough, so the
		// arrow gets out of the way — mirroring the Quest Journal's rat hand-off. Only applies
		// while the creature highlight is actually on to hand off to.
		if (config.highlightTargets() && targetNpcInSight())
		{
			return null;
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

	/** The arrow's kill-count caption, e.g. "12/30" (null = no active contract). */
	String progressText()
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

	/**
	 * Highlighter for the shared NPC-overlay service: tile highlight + minimap dot for every npc
	 * whose cache name matches the active contract's target. Membership is dynamic (the contract
	 * changes), so {@link #onVarbitChanged} rebuilds the service on every contract change; the
	 * render predicate re-checks live so a completed task blanks immediately. Note the service
	 * gives each npc at most ONE highlight (first registered highlighter wins) — so the tutorial
	 * rats, which the Quest Journal also highlights, are never double-drawn.
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

	/** True when [npc]'s cache name matches the active contract's target name. */
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

	/** The active contract target's standardized cache name (null = no contract / unresolvable). */
	private String targetName()
	{
		if (client.getGameState() != GameState.LOGGED_IN
			|| client.getVarpValue(VARP_SLAYER) == 0)
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

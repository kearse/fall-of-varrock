package net.runelite.client.plugins.lofcinematic;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Renderable;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.HotkeyListener;

/**
 * Cinematic Mode — a one-key toggle for recording clean promo footage:
 *  - hides the HUD (minimap, chatbox, inventory + tab stones) across fixed/resizable layouts,
 *  - unlocks camera pitch (overhead / low angles) and disables shake,
 *  - widens the zoom range (mirrors the stock Camera plugin's zoom-limit script hooks).
 *
 * Pairs with the server-side {@code ::cam} commands (CamMoveTo/LookAt/follow) which fly the camera;
 * this plugin just gets the framing clean. Off by default — enable it, then hit the toggle key.
 */
@PluginDescriptor(
	name = "Fall of Varrock Cinematic Mode",
	description = "Hide the HUD and unlock the camera for recording promo videos.",
	tags = {"lof", "cinematic", "camera", "promo", "video", "hud"},
	enabledByDefault = false
)
public class LofCinematicPlugin extends Plugin implements KeyListener
{
	private static final int YAW_MASK = 2047; // camera yaw is 11-bit and wraps 0..2047
	private static final int PITCH_MIN = 0;
	private static final int PITCH_MAX = 512; // with the pitch relaxer on, the camera tilts well past vanilla
	// HUD containers to hide. Only the active layout's widgets resolve non-null; the rest are skipped.
	private static final int[] HUD_COMPONENTS = {
		ComponentID.RESIZABLE_VIEWPORT_MINIMAP,
		ComponentID.RESIZABLE_VIEWPORT_INTERFACE_CONTAINER,
		ComponentID.RESIZABLE_VIEWPORT_INVENTORY_CONTAINER,
		ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP,
		ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INTERFACE_CONTAINER,
		ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_CONTAINER,
		ComponentID.FIXED_VIEWPORT_MINIMAP,
		ComponentID.FIXED_VIEWPORT_INTERFACE_CONTAINER,
		ComponentID.FIXED_VIEWPORT_INVENTORY_CONTAINER,
		ComponentID.CHATBOX_PARENT,
	};

	// The value the stock Camera plugin pushes for maximum zoom-in.
	private static final int INNER_ZOOM_LIMIT = 1004;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private KeyManager keyManager;
	@Inject private Hooks hooks;
	@Inject private LofCinematicConfig config;

	// Render hook used to hide the local player's model while flying the free-cam.
	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	private volatile boolean active;
	private volatile boolean freeCam;
	private final Set<Integer> hiddenByUs = new HashSet<>();

	// Arrow-key state for the manual camera rig (set on the AWT thread, read on the client thread).
	private volatile boolean panLeft, panRight, tiltUp, tiltDown;

	private final HotkeyListener toggleHotkey = new HotkeyListener(() -> config.toggleKey())
	{
		@Override
		public void hotkeyPressed()
		{
			active = !active;
			clientThread.invoke(LofCinematicPlugin.this::apply);
		}
	};

	private final HotkeyListener freeCamHotkey = new HotkeyListener(() -> config.freeCamKey())
	{
		@Override
		public void hotkeyPressed()
		{
			freeCam = !freeCam;
			clientThread.invoke(LofCinematicPlugin.this::applyFreeCam);
		}
	};

	@Provides
	LofCinematicConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofCinematicConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(toggleHotkey);
		keyManager.registerKeyListener(freeCamHotkey);
		keyManager.registerKeyListener(this);
		hooks.registerRenderableDrawListener(drawListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(toggleHotkey);
		keyManager.unregisterKeyListener(freeCamHotkey);
		keyManager.unregisterKeyListener(this);
		hooks.unregisterRenderableDrawListener(drawListener);
		active = false;
		freeCam = false;
		clientThread.invoke(() ->
		{
			apply();
			applyFreeCam();
		});
	}

	/** Enter/exit the native detached oculus free-camera (client-side; no server packet needed). */
	private void applyFreeCam()
	{
		client.setOculusOrbState(freeCam ? 1 : 0);
		if (freeCam)
		{
			client.setFreeCameraSpeed(config.freeCamSpeed());
		}
	}

	/** Hide the local player's model while flying the free-cam so its movement isn't on camera. */
	private boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		if (freeCam && config.hideSelfInFreeCam() && renderable == client.getLocalPlayer())
		{
			return false;
		}
		return true;
	}

	/** Apply camera state now; the HUD is hidden each tick while active and restored when toggled off. */
	private void apply()
	{
		client.setCameraPitchRelaxerEnabled(active && config.unlockCamera());
		client.setCameraShakeDisabled(active && config.disableShake());
		if (!active)
		{
			panLeft = panRight = tiltUp = tiltDown = false;
			restoreHud();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// If the user exited oculus natively (ESC), keep our flag in sync.
		if (freeCam && client.getOculusOrbState() == 0)
		{
			freeCam = false;
		}

		if (!active)
		{
			return;
		}

		if (config.hideHud())
		{
			for (int id : HUD_COMPONENTS)
			{
				Widget w = client.getWidget(id);
				if (w != null && !w.isHidden())
				{
					w.setHidden(true);
					hiddenByUs.add(id);
				}
			}
		}

		// Manual arrow-key camera rig: nudge the yaw/pitch targets while keys are held; the engine
		// eases the real camera toward them, so motion stays smooth.
		if (config.arrowCameraControl() && (panLeft || panRight || tiltUp || tiltDown))
		{
			int yaw = client.getCameraYawTarget();
			int pitch = client.getCameraPitchTarget();
			final int rs = config.rotateSpeed();
			final int ps = config.pitchSpeed();

			if (panLeft)
			{
				yaw = (yaw - rs) & YAW_MASK;
			}
			if (panRight)
			{
				yaw = (yaw + rs) & YAW_MASK;
			}
			final int up = config.invertArrowPitch() ? -ps : ps;
			if (tiltUp)
			{
				pitch = clampPitch(pitch + up);
			}
			if (tiltDown)
			{
				pitch = clampPitch(pitch - up);
			}

			client.setCameraYawTarget(yaw);
			client.setCameraPitchTarget(pitch);
		}
	}

	private static int clampPitch(int p)
	{
		return p < PITCH_MIN ? PITCH_MIN : (p > PITCH_MAX ? PITCH_MAX : p);
	}

	/** Re-show only the widgets WE hid (don't disturb ones the client already had hidden). */
	private void restoreHud()
	{
		for (int id : hiddenByUs)
		{
			Widget w = client.getWidget(id);
			if (w != null)
			{
				w.setHidden(false);
			}
		}
		hiddenByUs.clear();
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!active || !config.unlockZoom())
		{
			return;
		}
		// If any cache zoom overlay is outdated, leave zoom alone (same guard the Camera plugin uses).
		if (client.getIndexScripts().isOverlayOutdated())
		{
			return;
		}
		final int[] intStack = client.getIntStack();
		final int size = client.getIntStackSize();
		switch (event.getEventName())
		{
			case "innerZoomLimit":
				intStack[size - 1] = INNER_ZOOM_LIMIT;
				break;
			case "outerZoomLimit":
				intStack[size - 1] = config.zoomOutLimit();
				break;
		}
	}

	// --- KeyListener: arrow-key camera rig. handleArrow() only acts (and consumes) when cinematic mode +
	// arrow control are both on, so normal arrow behaviour is untouched otherwise. ---

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (handleArrow(e, true))
		{
			e.consume(); // stop the game's native arrow-camera from also moving (no double-speed)
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (handleArrow(e, false))
		{
			e.consume();
		}
	}

	private boolean handleArrow(KeyEvent e, boolean down)
	{
		if (freeCam || !active || !config.arrowCameraControl())
		{
			// In free-cam the oculus camera needs the arrows; otherwise only act in cinematic mode.
			return false;
		}
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_LEFT:
				panLeft = down;
				return true;
			case KeyEvent.VK_RIGHT:
				panRight = down;
				return true;
			case KeyEvent.VK_UP:
				tiltUp = down;
				return true;
			case KeyEvent.VK_DOWN:
				tiltDown = down;
				return true;
			default:
				return false;
		}
	}
}

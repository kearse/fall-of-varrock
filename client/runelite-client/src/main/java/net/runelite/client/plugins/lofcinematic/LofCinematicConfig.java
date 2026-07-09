package net.runelite.client.plugins.lofcinematic;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("lofcinematic")
public interface LofCinematicConfig extends Config
{
	@ConfigItem(
		keyName = "toggleKey",
		name = "Toggle key",
		description = "Hotkey to toggle Cinematic Mode on/off.",
		position = 1
	)
	default Keybind toggleKey()
	{
		return new Keybind(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "hideHud",
		name = "Hide HUD",
		description = "Hide the minimap, chatbox, inventory and tab stones for clean footage.",
		position = 2
	)
	default boolean hideHud()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockCamera",
		name = "Unlock camera pitch",
		description = "Allow looking much further up/down than normal (overhead and low angles).",
		position = 3
	)
	default boolean unlockCamera()
	{
		return true;
	}

	@ConfigItem(
		keyName = "disableShake",
		name = "Disable camera shake",
		description = "Suppress the engine's camera shake while recording.",
		position = 4
	)
	default boolean disableShake()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockZoom",
		name = "Unlock zoom range",
		description = "Allow zooming much further in and out for wide cinematic shots.",
		position = 5
	)
	default boolean unlockZoom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "zoomOutLimit",
		name = "Zoom-out distance",
		description = "How far the camera may zoom out (lower = further out). Default 64; vanilla is 128.",
		position = 6
	)
	default int zoomOutLimit()
	{
		return 64;
	}

	@ConfigItem(
		keyName = "arrowCameraControl",
		name = "Arrow-key camera",
		description = "While cinematic mode is on: ←/→ rotate the camera, ↑/↓ tilt it. Mouse wheel zooms.",
		position = 7
	)
	default boolean arrowCameraControl()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rotateSpeed",
		name = "Rotate speed",
		description = "How fast ←/→ rotate the camera (yaw units per frame).",
		position = 8
	)
	default int rotateSpeed()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "pitchSpeed",
		name = "Tilt speed",
		description = "How fast ↑/↓ tilt the camera (pitch units per frame).",
		position = 9
	)
	default int pitchSpeed()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "invertArrowPitch",
		name = "Invert tilt",
		description = "Flip which of ↑/↓ tilts the camera up vs down.",
		position = 10
	)
	default boolean invertArrowPitch()
	{
		return false;
	}

	@ConfigItem(
		keyName = "freeCamKey",
		name = "Free-cam toggle key",
		description = "Hotkey to enter/exit the detached free-fly (oculus) camera. Fly with arrow keys, mouse to look, ESC to exit.",
		position = 11
	)
	default Keybind freeCamKey()
	{
		return new Keybind(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "freeCamSpeed",
		name = "Free-cam fly speed",
		description = "How fast the detached free-cam moves forward/back/strafe (default 12).",
		position = 12
	)
	default int freeCamSpeed()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "hideSelfInFreeCam",
		name = "Hide my character in free-cam",
		description = "Hide your own character while flying the free-cam, so its movement doesn't show on camera.",
		position = 13
	)
	default boolean hideSelfInFreeCam()
	{
		return true;
	}
}

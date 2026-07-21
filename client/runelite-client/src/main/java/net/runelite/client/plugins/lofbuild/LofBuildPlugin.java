/*
 * Fall of Varrock — build stamp.
 *
 * Shows which client build a player is actually running, so a stale install is obvious at a
 * glance — to the player and to support looking at a screenshot. Two surfaces:
 *   1. the window title, stamped at ship time by client-build/PatchClient ("Fall of Varrock
 *      (<buildId>)"), visible even on the login screen; and
 *   2. this small in-game overlay, top-left, showing "FoV <buildId>".
 *
 * Why this matters: the FovLauncher can only ever replace the client jar, never the launcher
 * stub baked into the native install. A player whose launcher predates the classloader fix keeps
 * loading the install-day seed jar no matter how many updates ship — so their stamp stays OLD
 * while the server serves a NEW one. Reading an old date/sha here is the signature of that
 * "reinstall needed" state (auto-update alone won't fix them).
 *
 * The id comes from the /net/runelite/client/fov-build.properties resource PatchClient writes;
 * an unpatched local build has no resource and reads "dev".
 */
package net.runelite.client.plugins.lofbuild;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import javax.inject.Inject;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Build Stamp",
	description = "Shows the running client build (top-left + window title) so stale installs are obvious.",
	tags = {"lof", "build", "version", "stamp", "update"},
	enabledByDefault = true
)
public class LofBuildPlugin extends Plugin
{
	/** Matches the resource PatchClient writes; absent on unpatched local builds. */
	private static final String BUILD_RESOURCE = "/net/runelite/client/fov-build.properties";
	private static final String DEV = "dev";

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofBuildOverlay overlay;

	private String buildId = DEV;

	@Override
	protected void startUp()
	{
		buildId = readBuildId();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}

	String getBuildId()
	{
		return buildId;
	}

	private static String readBuildId()
	{
		try (InputStream in = LofBuildPlugin.class.getResourceAsStream(BUILD_RESOURCE))
		{
			if (in == null)
			{
				return DEV;
			}
			final Properties props = new Properties();
			props.load(in);
			final String id = props.getProperty("fov.build", DEV).trim();
			return id.isEmpty() ? DEV : id;
		}
		catch (IOException e)
		{
			return DEV;
		}
	}
}

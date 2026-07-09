/*
 * Kingdom of Lumbridge — server-driven on-screen alerts.
 *
 * Watches a server varplayer; when the server sets it non-zero, shows an
 * on-screen banner and fires a notification. The server raises the alert
 * with e.g. `::siegealert` (toggles the watched varp), or programmatically
 * from game logic such as the War Brain when a raid/siege begins.
 */
package net.runelite.client.plugins.lofalerts;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Kingdom of Lumbridge Alerts",
	description = "On-screen banners and notifications for server events (sieges, raids, etc.)",
	tags = {"lof", "alert", "siege", "war", "notification", "banner"},
	enabledByDefault = true
)
public class LofAlertsPlugin extends Plugin
{
	@Inject
	private Notifier notifier;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofAlertsOverlay overlay;

	@Inject
	private LofAlertsConfig config;

	private long bannerExpiresAt;

	@Provides
	LofAlertsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofAlertsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		bannerExpiresAt = 0L;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// The server pulses this varp to 1 to raise an alert, then resets it to 0.
		// The banner is duration-timed client-side, so we only fire on the rising
		// edge (value != 0) and ignore the reset — never persisting "active" state.
		if (event.getVarpId() == config.alertVarp() && event.getValue() != 0)
		{
			trigger();
		}
	}

	private void trigger()
	{
		if (config.showBanner())
		{
			final int seconds = Math.max(1, config.durationSeconds());
			bannerExpiresAt = System.currentTimeMillis() + seconds * 1000L;
		}

		if (config.playNotification())
		{
			notifier.notify(config.bannerText());
		}
	}

	/**
	 * @return the banner text while the banner should be visible, otherwise null.
	 */
	String getActiveBannerText()
	{
		if (bannerExpiresAt > 0L && System.currentTimeMillis() < bannerExpiresAt)
		{
			return config.bannerText();
		}
		return null;
	}
}

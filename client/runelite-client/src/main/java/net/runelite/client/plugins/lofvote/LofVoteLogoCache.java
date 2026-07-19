/*
 * Downloads + caches the toplists' vote-badge images for the vote window.
 *
 * Logo URLs are streamed by the server with each site row (their official badge
 * images, e.g. rsps-list.com/images/vote.jpg), so the artwork always comes from the
 * toplists themselves and adding a site never needs a client rebuild. Fetches are
 * async off the client thread; until an image lands (or if a site has no badge /
 * the fetch fails) the window draws its lettered fallback tile instead.
 *
 * Cache lives in ~/.fov-home/client/vote-logos/ (the client's brand home dir),
 * keyed by a hash of the URL — a changed badge URL is simply a new cache entry.
 */
package net.runelite.client.plugins.lofvote;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
class LofVoteLogoCache
{
	private final okhttp3.OkHttpClient http;
	private final ScheduledExecutorService executor;
	private final Path dir;

	/** URL → decoded image. Only successfully decoded images are ever put here. */
	private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();

	/** URLs with a fetch in flight or already failed — never re-request within a session. */
	private final Set<String> requested = ConcurrentHashMap.newKeySet();

	LofVoteLogoCache(okhttp3.OkHttpClient http, ScheduledExecutorService executor)
	{
		this.http = http;
		this.executor = executor;
		this.dir = Paths.get(System.getProperty("user.home"), ".fov-home", "client", "vote-logos");
	}

	/**
	 * The badge image for {@code url}, or null if it isn't available (yet). A null kicks
	 * off at most one async load per session; callers just draw the fallback tile and
	 * pick the image up on a later frame.
	 */
	BufferedImage get(String url)
	{
		if (url == null || !url.startsWith("https://"))
		{
			return null;
		}
		final BufferedImage cached = images.get(url);
		if (cached != null)
		{
			return cached;
		}
		if (requested.add(url))
		{
			executor.execute(() -> load(url));
		}
		return null;
	}

	private void load(String url)
	{
		try
		{
			final Path file = dir.resolve(keyFor(url));

			// Disk first: badges basically never change, so no revalidation.
			if (Files.isRegularFile(file))
			{
				final BufferedImage img = ImageIO.read(file.toFile());
				if (img != null)
				{
					images.put(url, img);
					return;
				}
				Files.deleteIfExists(file); // corrupt cache entry — refetch below
			}

			try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute())
			{
				if (!resp.isSuccessful() || resp.body() == null)
				{
					log.debug("vote logo fetch failed ({}): {}", resp.code(), url);
					return;
				}
				Files.createDirectories(dir);
				final Path tmp = dir.resolve(file.getFileName() + ".part");
				try (InputStream in = resp.body().byteStream())
				{
					Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}

			final BufferedImage img = ImageIO.read(file.toFile());
			if (img != null)
			{
				images.put(url, img);
			}
		}
		catch (Exception e)
		{
			log.debug("vote logo unavailable: {} ({})", url, e.toString());
		}
	}

	private static String keyFor(String url) throws Exception
	{
		final byte[] hash = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
		final StringBuilder sb = new StringBuilder("logo-");
		for (int i = 0; i < 10; i++)
		{
			sb.append(String.format("%02x", hash[i]));
		}
		return sb.append(".img").toString();
	}
}

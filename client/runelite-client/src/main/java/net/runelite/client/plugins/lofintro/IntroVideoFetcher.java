/*
 * Downloads + caches the intro video with ETag validation.
 *
 * Cache lives in ~/.fov-home/client/intro/ (the client's brand home dir). On every
 * client start we revalidate against the server: a swapped intro.mp4 on the VPS gets
 * a new ETag from Caddy's file_server, so already-installed clients refetch it
 * automatically — replacing the video never requires a client update.
 */
package net.runelite.client.plugins.lofintro;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
class IntroVideoFetcher
{
	private static final String DEFAULT_URL = "https://fallofvarrock.com/client/intro.mp4";

	private final okhttp3.OkHttpClient http;
	private final String url;
	private final Path dir;
	private final Path video;
	private final Path etagFile;
	private final CompletableFuture<File> ready = new CompletableFuture<>();

	IntroVideoFetcher(okhttp3.OkHttpClient http)
	{
		this.http = http;
		this.url = System.getProperty("fov.intro.url", DEFAULT_URL);
		this.dir = Paths.get(System.getProperty("user.home"), ".fov-home", "client", "intro");
		this.video = dir.resolve("intro.mp4");
		this.etagFile = dir.resolve("intro.mp4.etag");
	}

	/** Fetch/revalidate the video. Run off the client thread; completes {@link #ready} either way. */
	void prefetch()
	{
		try
		{
			refresh();
		}
		catch (Exception e)
		{
			log.warn("intro video fetch failed: {}", e.toString());
		}
		finally
		{
			if (Files.isRegularFile(video))
			{
				ready.complete(video.toFile());
			}
			else
			{
				ready.completeExceptionally(new IOException("intro video not available"));
			}
		}
	}

	/**
	 * @return the local video file, waiting up to {@code timeoutMs} for an in-flight
	 *         download; null if it never became available.
	 */
	File awaitVideo(long timeoutMs)
	{
		try
		{
			return ready.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (Exception e)
		{
			// Timeout or failed download — fall back to any cached copy from a previous run.
			return Files.isRegularFile(video) ? video.toFile() : null;
		}
	}

	private void refresh() throws IOException
	{
		Files.createDirectories(dir);

		final Request.Builder rb = new Request.Builder().url(url);
		final String etag = storedEtag();
		if (etag != null && Files.isRegularFile(video))
		{
			rb.header("If-None-Match", etag);
		}

		try (Response resp = http.newCall(rb.build()).execute())
		{
			if (resp.code() == 304)
			{
				log.debug("intro video cache is current");
				return;
			}
			if (!resp.isSuccessful() || resp.body() == null)
			{
				throw new IOException("HTTP " + resp.code() + " for " + url);
			}

			final Path part = dir.resolve("intro.mp4.part");
			try (InputStream in = resp.body().byteStream(); OutputStream out = Files.newOutputStream(part))
			{
				final byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) != -1)
				{
					out.write(buf, 0, n);
				}
			}
			Files.move(part, video, StandardCopyOption.REPLACE_EXISTING);

			final String newTag = resp.header("ETag");
			if (newTag != null)
			{
				Files.write(etagFile, newTag.getBytes(StandardCharsets.UTF_8));
			}
			else
			{
				Files.deleteIfExists(etagFile);
			}
			log.info("intro video downloaded ({} bytes)", Files.size(video));
		}
	}

	private String storedEtag()
	{
		try
		{
			return Files.isRegularFile(etagFile)
				? new String(Files.readAllBytes(etagFile), StandardCharsets.UTF_8).trim()
				: null;
		}
		catch (IOException e)
		{
			return null;
		}
	}
}

/*
 * Full-screen, unskippable video window.
 *
 * Swing shell + JavaFX media pipeline:
 *   - undecorated JDialog, APPLICATION_MODAL (blocks input to every other window of
 *     the app, i.e. the game), always-on-top, covers the whole screen, hidden cursor
 *   - JFXPanel hosting a MediaView, letterboxed on black
 *   - closes itself on end-of-media; there is no user-facing way to close it
 *   - failsafes so a decode stall can never trap the player forever: a hard cap from
 *     the moment the window shows, tightened to duration+grace once the media reports
 *     its length; any player/media error also closes it
 *
 * Everything FX is reached via Platform.runLater; the dialog itself lives on the EDT.
 * If the FX toolkit cannot start at all (e.g. no natives for this OS in the shaded
 * jar), play() logs and returns without showing anything.
 */
package net.runelite.client.plugins.lofintro;

import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class IntroVideoWindow
{
	/** Absolute ceiling on how long the window may exist, whatever the media does. */
	private static final int HARD_CAP_MS = 10 * 60 * 1000;

	/** Extra allowance past the reported media duration before force-closing. */
	private static final int END_GRACE_MS = 15 * 1000;

	private static final AtomicBoolean SHOWING = new AtomicBoolean();

	private IntroVideoWindow()
	{
	}

	/** Show the video full-screen and block the game until it finishes. Call on the EDT. */
	static void play(Window owner, File videoFile)
	{
		if (!SHOWING.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			show(owner, videoFile);
		}
		catch (Throwable t)
		{
			// e.g. FX toolkit failed to init (no natives on this platform) — skip the intro.
			log.warn("intro video playback unavailable: {}", t.toString());
			SHOWING.set(false);
		}
	}

	private static void show(Window owner, File videoFile)
	{
		final JFXPanel fxPanel = new JFXPanel(); // boots the FX toolkit
		Platform.setImplicitExit(false);

		final JDialog dialog = new JDialog(owner, Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setUndecorated(true);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Alt+F4 does nothing
		dialog.setAlwaysOnTop(true);
		dialog.getContentPane().setBackground(java.awt.Color.BLACK);
		dialog.setBackground(java.awt.Color.BLACK);

		final GraphicsConfiguration gc = owner != null
			? owner.getGraphicsConfiguration()
			: GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
		dialog.setBounds(gc.getBounds());

		final Cursor blank = Toolkit.getDefaultToolkit().createCustomCursor(
			new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");
		dialog.setCursor(blank);
		fxPanel.setCursor(blank);
		fxPanel.setBackground(java.awt.Color.BLACK);
		dialog.add(fxPanel);

		final AtomicReference<MediaPlayer> playerRef = new AtomicReference<>();
		final AtomicBoolean closed = new AtomicBoolean();
		final Runnable close = () ->
		{
			if (!closed.compareAndSet(false, true))
			{
				return;
			}
			Platform.runLater(() ->
			{
				final MediaPlayer mp = playerRef.get();
				if (mp != null)
				{
					try
					{
						mp.stop();
						mp.dispose();
					}
					catch (Throwable ignored)
					{
					}
				}
			});
			SwingUtilities.invokeLater(() ->
			{
				dialog.setVisible(false);
				dialog.dispose();
				SHOWING.set(false);
			});
		};

		final Timer hardCap = new Timer(HARD_CAP_MS, e -> close.run());
		hardCap.setRepeats(false);
		hardCap.start();

		Platform.runLater(() ->
		{
			try
			{
				final Media media = new Media(videoFile.toURI().toString());
				final MediaPlayer player = new MediaPlayer(media);
				playerRef.set(player);

				final MediaView view = new MediaView(player);
				view.setPreserveRatio(true);
				final StackPane root = new StackPane(view);
				root.setStyle("-fx-background-color: black;");
				final Scene scene = new Scene(root, javafx.scene.paint.Color.BLACK);
				view.fitWidthProperty().bind(scene.widthProperty());
				view.fitHeightProperty().bind(scene.heightProperty());
				fxPanel.setScene(scene);

				player.setOnEndOfMedia(close);
				player.setOnError(() ->
				{
					log.warn("intro video player error: {}", String.valueOf(player.getError()));
					close.run();
				});
				media.setOnError(() ->
				{
					log.warn("intro video media error: {}", String.valueOf(media.getError()));
					close.run();
				});
				player.setOnReady(() ->
				{
					final double durationMs = media.getDuration().toMillis();
					if (durationMs > 0 && !Double.isInfinite(durationMs) && !Double.isNaN(durationMs))
					{
						// Tighten the failsafe: close shortly after the video should have ended.
						SwingUtilities.invokeLater(() ->
						{
							hardCap.stop();
							final Timer t = new Timer((int) Math.min(HARD_CAP_MS, durationMs + END_GRACE_MS), e -> close.run());
							t.setRepeats(false);
							t.start();
						});
					}
				});
				player.play();
			}
			catch (Throwable t)
			{
				log.warn("intro video setup failed: {}", t.toString());
				close.run();
			}
		});

		// Modal: blocks here (pumping events) until close() disposes the dialog.
		dialog.setVisible(true);
		hardCap.stop();
	}
}

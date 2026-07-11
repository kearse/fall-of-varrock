/*
 * Unskippable video overlay docked over the game viewport.
 *
 * Swing shell + JavaFX media pipeline:
 *   - undecorated modeless JDialog OWNED by the client frame, sized to the game
 *     canvas and tracking it live. Owned windows always stack above their owner and
 *     minimize/hide with it, so the video behaves like part of the client: the player
 *     can move, resize, minimize the client or alt-tab to other apps — but the game
 *     viewport itself stays covered and there is no way to dismiss the video.
 *     (Deliberately NOT application-modal / always-on-top: that made the video hold
 *     the whole desktop hostage. And deliberately not a Swing overlay inside the
 *     frame: the game canvas is a heavyweight component blitting via BufferStrategy,
 *     which repaints over lightweight components.)
 *   - JFXPanel hosting a MediaView, letterboxed on black
 *   - never steals keyboard focus (focusableWindowState=false)
 *   - closes itself on end-of-media; failsafes so a decode stall can never trap the
 *     player: any player/media error, duration+grace once known, and a hard cap
 *
 * Everything FX is reached via Platform.runLater; the dialog lives on the EDT. If the
 * FX toolkit cannot start at all (e.g. no natives for this OS in the shaded jar),
 * play() logs and returns without showing anything.
 */
package net.runelite.client.plugins.lofintro;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
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
	/** Absolute ceiling on how long the overlay may exist, whatever the media does. */
	private static final int HARD_CAP_MS = 10 * 60 * 1000;

	/** Extra allowance past the reported media duration before force-closing. */
	private static final int END_GRACE_MS = 15 * 1000;

	private static final AtomicBoolean SHOWING = new AtomicBoolean();

	private IntroVideoWindow()
	{
	}

	/** Dock the video over the game canvas until it finishes. Call on the EDT. */
	static void play(Canvas canvas, File videoFile)
	{
		if (canvas == null || !canvas.isShowing())
		{
			log.warn("intro video skipped: game canvas not available");
			return;
		}
		if (!SHOWING.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			show(canvas, videoFile);
		}
		catch (Throwable t)
		{
			// e.g. FX toolkit failed to init (no natives on this platform) — skip the intro.
			log.warn("intro video playback unavailable: {}", t.toString());
			SHOWING.set(false);
		}
	}

	private static void show(Canvas canvas, File videoFile)
	{
		final JFXPanel fxPanel = new JFXPanel(); // boots the FX toolkit
		Platform.setImplicitExit(false);

		final Window frame = SwingUtilities.getWindowAncestor(canvas);
		final JDialog dialog = new JDialog(frame);
		dialog.setUndecorated(true);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		// Never take keyboard focus: the client keeps behaving normally around the video.
		dialog.setFocusableWindowState(false);
		dialog.setAutoRequestFocus(false);
		dialog.getContentPane().setBackground(java.awt.Color.BLACK);
		dialog.setBackground(java.awt.Color.BLACK);
		fxPanel.setBackground(java.awt.Color.BLACK);
		dialog.add(fxPanel);

		// Follow the game viewport: cover exactly the canvas, wherever it goes.
		final Runnable reposition = () ->
		{
			if (canvas.isShowing())
			{
				final Point p = canvas.getLocationOnScreen();
				dialog.setBounds(new Rectangle(p.x, p.y, canvas.getWidth(), canvas.getHeight()));
			}
		};
		final ComponentListener tracker = new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				reposition.run();
			}

			@Override
			public void componentMoved(ComponentEvent e)
			{
				reposition.run();
			}
		};
		canvas.addComponentListener(tracker);
		if (frame != null)
		{
			frame.addComponentListener(tracker);
		}

		final AtomicReference<MediaPlayer> playerRef = new AtomicReference<>();
		final AtomicBoolean closed = new AtomicBoolean();
		final AtomicReference<Runnable> closeRef = new AtomicReference<>();
		final Timer hardCap = new Timer(HARD_CAP_MS, e -> closeRef.get().run());
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
				hardCap.stop();
				canvas.removeComponentListener(tracker);
				if (frame != null)
				{
					frame.removeComponentListener(tracker);
				}
				dialog.setVisible(false);
				dialog.dispose();
				SHOWING.set(false);
			});
		};

		closeRef.set(close);
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
							if (!closed.get())
							{
								hardCap.stop();
								final Timer t = new Timer((int) Math.min(HARD_CAP_MS, durationMs + END_GRACE_MS), ev -> close.run());
								t.setRepeats(false);
								t.start();
							}
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

		reposition.run();
		dialog.setVisible(true); // modeless: returns immediately, the video runs on its own
	}
}

/*
 * Fall of Varrock — self-updating launcher stub.
 *
 * This is the app jpackage installs. On every run it:
 *   1. reads the remote client hash  (fallofvarrock.com/client/fov-client.jar.sha256)
 *   2. compares it to the local copy in ~/.fov-home/client/fov-client.jar
 *   3. if they differ, refreshes the local copy — from the bundled seed jar if that
 *      already matches (no download), otherwise by downloading the new jar behind a
 *      splash with a real progress bar (percent, MB, speed, time left, stall notice)
 *   4. launches RuneLite in-process from the local copy
 *
 * Result: the INSTALLER rarely changes; shipping a client update is just re-hosting
 * fov-client.jar + its .sha256 — every player auto-updates on next launch, no reinstall.
 * Offline / server-down is non-fatal: it launches whatever local copy exists.
 */
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.HttpURLConnection;
import java.nio.file.*;
import java.security.MessageDigest;

public final class FovLauncher {

    static final String BASE = "https://fallofvarrock.com/client/";
    static final String JAR_NAME = "fov-client.jar";
    static final String JAV_CONFIG = BASE + "jav_config_standalone.ws";
    static final String MAIN_CLASS = "net.runelite.client.RuneLite";

    public static void main(String[] args) {
        // jpackage builds a Windows GUI app (no console): an exception escaping main
        // makes the app vanish a second after launch with nothing on screen and nothing
        // written down. Everything below is funnelled into a log file + an error dialog
        // so a failed launch is always reportable.
        try {
            run(args);
        } catch (Throwable t) {
            fatal(t);
        }
    }

    private static void run(String[] args) throws Exception {
        Path liveDir = Paths.get(System.getProperty("user.home"), ".fov-home", "client");
        Files.createDirectories(liveDir);
        Path live = liveDir.resolve(JAR_NAME);
        Path seed = seedJar();

        try {
            String remote = httpText(BASE + JAR_NAME + ".sha256");
            if (remote != null) {
                remote = remote.trim().toLowerCase();
                String localHash = Files.exists(live) ? sha256(live) : "";
                if (!remote.equals(localHash)) {
                    if (seed != null && remote.equals(sha256(seed))) {
                        Files.copy(seed, live, StandardCopyOption.REPLACE_EXISTING); // seed is current — no download
                    } else {
                        Splash s = Splash.show();
                        try { download(BASE + JAR_NAME, live, remote, s); } finally { s.close(); }
                    }
                }
            }
        } catch (Exception updateFailed) {
            // Non-fatal: fall through and launch whatever we have.
            System.err.println("[launcher] update check failed: " + updateFailed);
        }

        // Ensure SOMETHING is present to launch (first run, offline): fall back to seed.
        if (!Files.exists(live)) {
            if (seed == null) throw new IllegalStateException("No client jar available and update failed.");
            Files.copy(seed, live, StandardCopyOption.REPLACE_EXISTING);
        }

        launch(live, args);
    }

    /** The seed fov-client.jar shipped in the app dir next to this launcher. */
    private static Path seedJar() {
        try {
            Path self = Paths.get(FovLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = self.getParent().resolve(JAR_NAME);
            return Files.exists(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void launch(Path jar, String[] args) throws Exception {
        System.setProperty("runelite.launcher.version", "2.7.4-SNAPSHOT");
        // Parent = PLATFORM classloader, deliberately NOT this class's loader: jpackage
        // puts every jar in the app dir on the app classpath, including the bundled seed
        // fov-client.jar. With the app classloader as parent, parent-first delegation
        // resolved every net.runelite class from the STALE SEED and the freshly updated
        // ~/.fov-home jar was never actually used (clients were pinned to install-day
        // code). The platform loader has all JDK classes and none of the app's.
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        Class<?> rl = Class.forName(MAIN_CLASS, true, cl);
        Method main = rl.getMethod("main", String[].class);
        String[] rlArgs = new String[]{"--jav_config=" + JAV_CONFIG};
        main.invoke(null, (Object) rlArgs);
    }

    private static String httpText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (c.getResponseCode() != 200) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            return r.readLine();
        }
    }

    /**
     * Download to a .part file and only promote it once it hashes to `expectSha`.
     * Without the check a truncated or proxy-mangled body was moved into place as if
     * it were the real client, and every later launch died loading classes out of it.
     */
    private static void download(String url, Path dest, String expectSha, Splash splash) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new IOException("client download returned HTTP " + c.getResponseCode());
        // Caddy serves the jar as a plain static file, so this is the real size; -1 only
        // if some proxy strips it, in which case the splash falls back to "MB so far".
        long total = c.getContentLengthLong();
        splash.downloading(0, total);
        // Per-process temp name: players open multiple clients, and two launchers
        // updating at once must not interleave writes into one .part file.
        Path tmp = dest.resolveSibling(JAR_NAME + ".part." + ProcessHandle.current().pid());
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                splash.downloading(done, total);
            }
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
        splash.verifying();
        String got = sha256(tmp);
        if (!got.equals(expectSha)) {
            Files.deleteIfExists(tmp);
            throw new IOException("downloaded client is corrupt (sha256 " + got + " != " + expectSha + ")");
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Last-resort reporting: write ~/.fov-home/launcher.log and show the player a dialog. */
    private static void fatal(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        System.err.println(trace);
        Path log = Paths.get(System.getProperty("user.home"), ".fov-home", "launcher.log");
        try {
            Files.createDirectories(log.getParent());
            Files.write(log, trace.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
        try {
            JOptionPane.showMessageDialog(null,
                    "Fall of Varrock could not start.\n\n" + t + "\n\nDetails were written to:\n" + log
                            + "\n\nPlease send that file to us on Discord.",
                    "Fall of Varrock", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
        }
        System.exit(1);
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * "Updating…" splash shown only while an actual download runs.
     *
     * Shows a real progress bar (percent, MB done / total, speed, time left) rather than
     * a bouncing one: on a slow connection a ~30MB pull can take minutes and players
     * could not tell "slow" from "stuck". If the server stops sending for a few seconds
     * the detail line says so explicitly, so a hung download is visibly hung.
     *
     * The download thread only stores counters; a Swing timer repaints from them ten
     * times a second, so a fast connection can't flood the event thread with updates.
     */
    static final class Splash {
        static final Color BG = new Color(0x08, 0x05, 0x06);
        static final Color RED = new Color(0xDC, 0x26, 0x26);
        static final Color TEXT = new Color(0xE8, 0xD8, 0xB0);
        static final Color DIM = new Color(0x9A, 0x8E, 0x78);
        static final long STALL_AFTER_MS = 4000;

        final JWindow w;
        final JProgressBar bar;
        final JLabel detail;
        final javax.swing.Timer timer;

        // Written by the download thread, read by the Swing timer.
        volatile long done, total = -1;
        volatile boolean verifying;
        final long startedAt = System.currentTimeMillis();
        volatile long lastChangeAt = startedAt;

        // Speed sampling, only touched on the Swing thread.
        long sampleAt = startedAt, sampleBytes;
        double bytesPerSec;

        private Splash(JWindow w, JProgressBar bar, JLabel detail) {
            this.w = w;
            this.bar = bar;
            this.detail = detail;
            if (w == null) {
                timer = null;
            } else {
                timer = new javax.swing.Timer(100, e -> repaint());
                timer.start();
            }
        }

        static Splash show() {
            final JWindow[] win = new JWindow[1];
            final JProgressBar[] bar = new JProgressBar[1];
            final JLabel[] detail = new JLabel[1];
            try {
                Runnable r = () -> {
                    JWindow win0 = new JWindow();
                    JPanel p = new JPanel(new BorderLayout(0, 10));
                    p.setBackground(BG);
                    p.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(RED),
                            BorderFactory.createEmptyBorder(22, 32, 20, 32)));

                    JLabel title = new JLabel("Updating Fall of Varrock…", SwingConstants.CENTER);
                    title.setForeground(TEXT);
                    title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));

                    UIManager.put("ProgressBar.selectionForeground", TEXT);
                    UIManager.put("ProgressBar.selectionBackground", TEXT);
                    JProgressBar bar0 = new JProgressBar(0, 1000);
                    bar0.setIndeterminate(true);
                    bar0.setStringPainted(true);
                    bar0.setString("Connecting…");
                    bar0.setForeground(RED);
                    bar0.setBackground(new Color(0x20, 0x14, 0x14));
                    bar0.setBorderPainted(false);
                    bar0.setPreferredSize(new Dimension(420, 22));

                    JLabel detail0 = new JLabel(" ", SwingConstants.CENTER);
                    detail0.setForeground(DIM);
                    detail0.setFont(detail0.getFont().deriveFont(Font.PLAIN, 12f));
                    // Fixed width so the window never re-lays out as the text changes.
                    detail0.setPreferredSize(new Dimension(420, 18));

                    p.add(title, BorderLayout.NORTH);
                    p.add(bar0, BorderLayout.CENTER);
                    p.add(detail0, BorderLayout.SOUTH);
                    win0.add(p);
                    win0.pack();
                    win0.setLocationRelativeTo(null);
                    win0.setAlwaysOnTop(true);
                    win0.setVisible(true);
                    win[0] = win0;
                    bar[0] = bar0;
                    detail[0] = detail0;
                };
                if (SwingUtilities.isEventDispatchThread()) r.run();
                else SwingUtilities.invokeAndWait(r);
            } catch (Throwable ignored) {
                // Headless or a broken toolkit: run without a window, never block the update.
            }
            return new Splash(win[0], bar[0], detail[0]);
        }

        /** Called from the download thread for every chunk read. Cheap: just stores counters. */
        void downloading(long doneBytes, long totalBytes) {
            if (doneBytes != done) lastChangeAt = System.currentTimeMillis();
            done = doneBytes;
            total = totalBytes;
        }

        void verifying() {
            verifying = true;
        }

        /** Swing-thread repaint from the counters. */
        private void repaint() {
            long now = System.currentTimeMillis();
            long d = done, t = total;

            if (verifying) {
                bar.setIndeterminate(false);
                bar.setValue(bar.getMaximum());
                bar.setString("Verifying download…");
                detail.setText(mb(d) + " MB downloaded in " + secs(now - startedAt));
                return;
            }

            // Speed: sample every ~half second, smoothed so the number doesn't flicker.
            if (now - sampleAt >= 500) {
                double inst = (d - sampleBytes) * 1000.0 / (now - sampleAt);
                bytesPerSec = bytesPerSec == 0 ? inst : bytesPerSec * 0.6 + inst * 0.4;
                sampleAt = now;
                sampleBytes = d;
            }

            StringBuilder line = new StringBuilder();
            if (t > 0) {
                bar.setIndeterminate(false);
                bar.setValue((int) Math.min(bar.getMaximum(), d * bar.getMaximum() / t));
                bar.setString((int) (d * 100 / t) + "%");
                line.append(mb(d)).append(" of ").append(mb(t)).append(" MB");
            } else {
                // No Content-Length: can't show a percentage, but do show movement.
                bar.setIndeterminate(true);
                bar.setString(d == 0 ? "Connecting…" : "Downloading…");
                line.append(mb(d)).append(" MB so far");
            }

            long sinceData = now - lastChangeAt;
            if (d > 0 && sinceData > STALL_AFTER_MS) {
                line.append("  •  no data for ").append(sinceData / 1000).append("s, still waiting on the server…");
                bytesPerSec = 0; // forget the pre-stall rate so the ETA is honest once data resumes
            } else if (bytesPerSec > 0) {
                line.append("  •  ").append(speed(bytesPerSec));
                if (t > 0 && d < t) {
                    line.append("  •  about ").append(secs((long) ((t - d) * 1000 / bytesPerSec))).append(" left");
                }
            }
            detail.setText(line.toString());
        }

        void close() {
            if (w != null) SwingUtilities.invokeLater(() -> { timer.stop(); w.dispose(); });
        }

        static String mb(long bytes) {
            return String.format("%.1f", bytes / 1048576.0);
        }

        static String speed(double bytesPerSec) {
            return bytesPerSec >= 1048576
                    ? String.format("%.1f MB/s", bytesPerSec / 1048576)
                    : String.format("%.0f KB/s", bytesPerSec / 1024);
        }

        static String secs(long millis) {
            long s = Math.max(1, (millis + 500) / 1000);
            return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
        }
    }
}

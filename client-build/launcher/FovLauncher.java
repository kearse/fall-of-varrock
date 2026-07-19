/*
 * Fall of Varrock — self-updating launcher stub.
 *
 * This is the app jpackage installs. On every run it:
 *   1. reads the remote client hash  (fallofvarrock.com/client/fov-client.jar.sha256)
 *   2. compares it to the local copy in ~/.fov-home/client/fov-client.jar
 *   3. if they differ, refreshes the local copy — from the bundled seed jar if that
 *      already matches (no download), otherwise by downloading the new jar
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
                        try { download(BASE + JAR_NAME, live, remote); } finally { s.close(); }
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
    private static void download(String url, Path dest, String expectSha) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new IOException("client download returned HTTP " + c.getResponseCode());
        Path tmp = dest.resolveSibling(JAR_NAME + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
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

    /** Tiny "Updating…" splash shown only while an actual download runs. */
    static final class Splash {
        final JWindow w;
        private Splash(JWindow w) { this.w = w; }
        static Splash show() {
            final JWindow[] holder = new JWindow[1];
            try {
                Runnable r = () -> {
                    JWindow win = new JWindow();
                    JPanel p = new JPanel(new BorderLayout());
                    p.setBackground(new Color(0x08, 0x05, 0x06));
                    p.setBorder(BorderFactory.createLineBorder(new Color(0xDC, 0x26, 0x26)));
                    JLabel l = new JLabel("Updating Fall of Varrock…", SwingConstants.CENTER);
                    l.setForeground(new Color(0xE8, 0xD8, 0xB0));
                    l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
                    l.setBorder(BorderFactory.createEmptyBorder(22, 40, 14, 40));
                    JProgressBar bar = new JProgressBar();
                    bar.setIndeterminate(true);
                    bar.setBorder(BorderFactory.createEmptyBorder(0, 24, 20, 24));
                    p.add(l, BorderLayout.CENTER);
                    p.add(bar, BorderLayout.SOUTH);
                    win.add(p);
                    win.pack();
                    win.setLocationRelativeTo(null);
                    win.setVisible(true);
                    holder[0] = win;
                };
                if (SwingUtilities.isEventDispatchThread()) r.run();
                else SwingUtilities.invokeAndWait(r);
            } catch (Exception ignored) {}
            return new Splash(holder[0]);
        }
        void close() {
            if (w != null) SwingUtilities.invokeLater(w::dispose);
        }
    }
}

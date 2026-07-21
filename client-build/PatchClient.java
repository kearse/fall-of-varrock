/*
 * Fall of Varrock — build-time client patcher.
 *
 * Bakes into a built RuneLite client jar the same transforms RSProx applies at
 * runtime, so the resulting jar connects DIRECTLY to our server with our RSA key —
 * no proxy in the loop. This is what lets players run a downloaded client instead
 * of RSProx.
 *
 * Ported from the decompiled net.rsprox.patch.runelite.RuneLitePatcher
 * (C:\Users\zakea\rsps-client\decomp-launcher). Two byte-level transforms — the
 * ones that don't need ASM — live here:
 *
 *   1. overwriteModulus : find the class carrying the RSA exponent "10001", locate
 *      the 256-hex-char modulus string next to it (length-prefixed constant-pool
 *      UTF-8), and replace it with ours. This is why the login screen encrypts
 *      with OUR key.
 *   2. overwriteLocalHost : blank the hardcoded "127.0.0.1" so the client uses the
 *      world address from our jav_config world list (play.fallofvarrock.com).
 *
 * The ClientLoader ".jagex.com" host-check removal + gamepack-port rewrite are ASM
 * transforms (ClientLoaderPatcher / WorldClientPatcher) added in the next build step.
 *
 * Usage:
 *   javac PatchClient.java
 *   java PatchClient <in.jar> <out.jar> <modulusHex>
 */
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class PatchClient {

    /** Our world config — baked in as the client's default jav_config (see shaded mode). */
    private static final String JAV_CONFIG = "https://fallofvarrock.com/client/jav_config_standalone.ws";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java PatchClient <injected|wrapper|shaded> <in.jar> <out.jar> [modulusHex] [buildId]");
            System.err.println("  injected : bake our RSA modulus + blank localhost (injected-client jar)");
            System.err.println("  wrapper  : blank .jagex.com/.runescape.com host checks (client wrapper jar)");
            System.err.println("  shaded   : all of the above + title/config-dir + optional buildId stamp");
            System.exit(2);
        }
        String mode = args[0];
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        Map<String, byte[]> zip = readZip(in);

        // Each transform mirrors exactly what RSProx does to that specific jar, so we
        // don't touch host constants in the injected-client (they can be load-bearing).
        if (mode.equals("injected")) {
            if (args.length < 4) throw new IllegalArgumentException("injected mode needs <modulusHex>");
            String modulus = args[3].trim();
            if (!modulus.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("modulus must be hex");
            String old = overwriteModulus(zip, modulus);
            if (old == null) throw new IllegalStateException("Modulus not found — is this the injected-client jar?");
            System.out.println("[modulus] " + old.substring(0, 24) + "… -> " + modulus.substring(0, 24) + "…");
            System.out.println("[localhost] " + (overwriteLocalHost(zip) ? "blanked" : "not present (ok)"));
        } else if (mode.equals("wrapper")) {
            boolean j = blankConstant(zip, ".jagex.com");
            boolean r = blankConstant(zip, ".runescape.com");
            System.out.println("[hostcheck] .jagex.com " + (j ? "blanked" : "absent") + ", .runescape.com " + (r ? "blanked" : "absent"));
            if (!j && !r) throw new IllegalStateException("No host check found — is this the client wrapper jar?");
        } else if (mode.equals("shaded")) {
            // One fat jar carrying both the injected-client (modulus/localhost) and the
            // ClientLoader wrapper (host checks). Apply everything in a single pass.
            if (args.length < 4) throw new IllegalArgumentException("shaded mode needs <modulusHex>");
            String modulus = args[3].trim();
            if (!modulus.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("modulus must be hex");
            // Optional build id (e.g. "2026-07-21.a906ab4"). Stamped into the window title and a
            // jar resource so a stale install is obvious at a glance — the launcher can only swap
            // the client jar, never itself, so a player pinned to old code (see FovLauncher's
            // classloader note) shows an old stamp even though updates ship. "dev" = local build.
            String buildId = args.length >= 5 ? args[4].trim() : "dev";
            if (!buildId.matches("[0-9A-Za-z._-]{1,40}")) throw new IllegalArgumentException("build id must be [0-9A-Za-z._-], <=40 chars");
            String title = buildId.equals("dev") ? "Fall of Varrock" : "Fall of Varrock (" + buildId + ")";
            String old = overwriteModulus(zip, modulus);
            if (old == null) throw new IllegalStateException("Modulus not found in shaded jar");
            System.out.println("[modulus] " + old.substring(0, 24) + "… -> " + modulus.substring(0, 24) + "…");
            System.out.println("[localhost] " + (overwriteLocalHost(zip) ? "blanked" : "not present (ok)"));
            boolean j = blankConstant(zip, ".jagex.com");
            boolean r = blankConstant(zip, ".runescape.com");
            System.out.println("[hostcheck] .jagex.com " + (j ? "blanked" : "absent") + ", .runescape.com " + (r ? "blanked" : "absent"));
            System.out.println("[build] " + buildId + (writeBuildStamp(zip, buildId) ? " (stamped)" : " (resource write failed)"));
            System.out.println("[title] " + (setRuneliteTitle(zip, title) ? "set to '" + title + "'" : "properties not found"));
            // Point the DEFAULT jav_config at our world config. Without this the jar only
            // works when launched with --jav_config=... (the launcher passes it); a player
            // who double-clicks the bare fov-client.jar fell through to Jagex's live
            // config, downloaded the real gamepack and died on jar verification
            // ("Unable to verify jar entry: META-INF/" -> RuneLite's critical-error box).
            System.out.println("[jav_config] " + (setJavConfig(zip, JAV_CONFIG) ? "-> " + JAV_CONFIG : "properties not found"));
            // Use our own config dir (~/.fov-home) instead of ~/.runelite, so the client
            // never reads the user's real RuneLite/Jagex account session (the "Play Now /
            // <account>" screen). Same trick RSProx uses (.runelite -> .rlcustom). Must be
            // <= 9 chars to fit the ".runelite" constant slot; ".fov-home" is exactly 9.
            int renamed = replaceConstant(zip, ".runelite", ".fov-home");
            System.out.println("[configdir] .runelite -> .fov-home (" + renamed + " constant(s))");
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }

        writeZip(zip, out);
        System.out.println("[done] -> " + out);
    }

    // --- patch-target guard --------------------------------------------------

    /**
     * Entries the byte-scans must never touch: the JavaFX runtime (shaded in for the
     * intro video) and any native library. A stray needle match in a DLL would corrupt
     * it AND leave the real target unpatched (the scans stop at the first hit).
     */
    private static boolean isPatchable(String name) {
        if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) return false;
        return !(name.startsWith("javafx/")
                || name.startsWith("com/sun/javafx/")
                || name.startsWith("com/sun/glass/")
                || name.startsWith("com/sun/prism/")
                || name.startsWith("com/sun/media/")
                || name.startsWith("com/sun/scenario/")
                || name.startsWith("com/sun/pisces/")
                || name.startsWith("com/sun/marlin/")
                || name.startsWith("com/sun/openpisces/"));
    }

    // --- modulus -----------------------------------------------------------

    /** Find the entry containing the RSA exponent "10001", patch its modulus. Returns null if absent. */
    private static String overwriteModulus(Map<String, byte[]> zip, String rsa) {
        byte[] needle = "10001".getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> e : zip.entrySet()) {
            if (!isPatchable(e.getKey())) continue;
            byte[] bytes = e.getValue();
            if (indexOf(bytes, needle, 0) == -1) continue;
            String[] old = new String[1];
            byte[] patched = patchModulus(bytes, rsa, old);
            if (patched == null) continue; // exponent present but no modulus slice here
            e.setValue(patched);
            return old[0];
        }
        return null;
    }

    /**
     * Replace a whole constant-pool string equal to `from` with `to` (to.length <= from.length),
     * validated by the u2 length prefix so we never hit a substring of a longer constant.
     * Returns how many constants were replaced.
     */
    private static int replaceConstant(Map<String, byte[]> zip, String from, String to) {
        byte[] needle = from.getBytes(StandardCharsets.UTF_8);
        int len = needle.length, count = 0;
        for (Map.Entry<String, byte[]> e : zip.entrySet()) {
            if (!isPatchable(e.getKey())) continue;
            byte[] bytes = e.getValue();
            int at = 0, idx;
            while ((idx = indexOf(bytes, needle, at)) != -1) {
                boolean whole = idx >= 2 && (((bytes[idx - 2] & 0xFF) << 8) | (bytes[idx - 1] & 0xFF)) == len;
                if (whole) { bytes = setString(bytes, idx, to); e.setValue(bytes); count++; at = idx; }
                else at = idx + 1;
            }
        }
        return count;
    }

    /**
     * Bake the build id into a jar resource the client reads at runtime (the "Fall of Varrock
     * Build Stamp" overlay). New entry, so it works whether or not one already exists. Returns
     * false only if the map itself somehow can't hold it (never, in practice).
     */
    private static boolean writeBuildStamp(Map<String, byte[]> zip, String buildId) {
        String name = "net/runelite/client/fov-build.properties";
        String body = "# Generated by client-build/PatchClient at ship time.\n"
                + "fov.build=" + buildId + "\n";
        zip.put(name, body.getBytes(StandardCharsets.ISO_8859_1));
        return zip.containsKey(name);
    }

    /** Rewrite the runelite.title line in runelite.properties (a plain-text resource). */
    private static boolean setRuneliteTitle(Map<String, byte[]> zip, String title) {
        String name = "net/runelite/client/runelite.properties";
        byte[] props = zip.get(name);
        if (props == null) return false;
        String text = new String(props, StandardCharsets.ISO_8859_1);
        text = text.replaceAll("(?m)^runelite\\.title=.*$", "runelite.title=" + title);
        zip.put(name, text.getBytes(StandardCharsets.ISO_8859_1));
        return true;
    }

    /**
     * Rewrite both jav_config lines in runelite.properties. The backup gets the same URL:
     * RuneLite falls back to it when the primary fails, and Jagex's static config would
     * send the client to the wrong game.
     */
    private static boolean setJavConfig(Map<String, byte[]> zip, String url) {
        String name = "net/runelite/client/runelite.properties";
        byte[] props = zip.get(name);
        if (props == null) return false;
        String text = new String(props, StandardCharsets.ISO_8859_1);
        text = text.replaceAll("(?m)^runelite\\.jav_config=.*$", "runelite.jav_config=" + url);
        text = text.replaceAll("(?m)^runelite\\.jav_config_backup=.*$", "runelite.jav_config_backup=" + url);
        zip.put(name, text.getBytes(StandardCharsets.ISO_8859_1));
        return true;
    }

    /**
     * Blank a constant-pool string EXACTLY equal to `literal` (a whole UTF-8 constant,
     * not a substring of a longer one like a full URL). We confirm it's a real constant
     * by checking the preceding u2 length prefix == literal.length() — the byte-level
     * equivalent of RSProx's ASM "LdcInsnNode.cst equals" match.
     */
    private static boolean blankConstant(Map<String, byte[]> zip, String literal) {
        byte[] needle = literal.getBytes(StandardCharsets.UTF_8);
        int len = needle.length;
        boolean any = false;
        for (Map.Entry<String, byte[]> e : zip.entrySet()) {
            if (!isPatchable(e.getKey())) continue;
            byte[] bytes = e.getValue();
            int from = 0, idx;
            while ((idx = indexOf(bytes, needle, from)) != -1) {
                boolean isWholeConstant = idx >= 2
                        && (((bytes[idx - 2] & 0xFF) << 8) | (bytes[idx - 1] & 0xFF)) == len;
                if (isWholeConstant) {
                    bytes = setString(bytes, idx, "");
                    e.setValue(bytes);
                    any = true;
                    from = idx; // array shrank; keep scanning from here
                } else {
                    from = idx + 1; // substring hit — skip past it
                }
            }
        }
        return any;
    }

    /** Locate the first run of >=256 hex chars (the modulus constant) and swap it. */
    private static byte[] patchModulus(byte[] bytes, String replacement, String[] oldOut) {
        int[] range = firstHexSlice(bytes, 256);
        if (range == null) return null;
        int first = range[0], last = range[1];
        // Boundaries must be non-hex (a clean constant, not part of a longer run).
        if (isHex((char) bytes[first - 1]) || isHex((char) bytes[last + 1])) return null;
        oldOut[0] = new String(bytes, first, last - first + 1, StandardCharsets.UTF_8);
        if (replacement.length() > (last - first + 1))
            throw new IllegalStateException("New modulus cannot be larger than the old (" + (last - first + 1) + ")");
        return setString(bytes, first, replacement);
    }

    // --- localhost ---------------------------------------------------------

    private static boolean overwriteLocalHost(Map<String, byte[]> zip) {
        byte[] needle = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> e : zip.entrySet()) {
            if (!isPatchable(e.getKey())) continue;
            if (indexOf(e.getValue(), needle, 0) == -1) continue;
            e.setValue(setString(e.getValue(), indexOf(e.getValue(), needle, 0), ""));
            return true;
        }
        return false;
    }

    // --- constant-pool UTF-8 string rewrite (length-prefixed) --------------

    /**
     * Replace the java-class-file modified-UTF8 string that STARTS at stringStartIndex.
     * The two bytes before the start are the u2 length; rewrite them + shift the tail.
     * Faithful port of RuneLitePatcher.setString.
     */
    private static byte[] setString(byte[] src, int start, String repl) {
        int oldLen = ((src[start - 2] & 0xFF) << 8) | (src[start - 1] & 0xFF);
        int delta = repl.length() - oldLen;
        byte[] out = new byte[src.length + delta];
        System.arraycopy(src, 0, out, 0, start - 2);
        if (repl.length() < 0 || repl.length() >= 65535) throw new IllegalStateException("string too long");
        out[start - 2] = (byte) ((repl.length() >>> 8) & 0xFF);
        out[start - 1] = (byte) (repl.length() & 0xFF);
        byte[] rb = repl.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(rb, 0, out, start, rb.length);
        System.arraycopy(src, start + oldLen, out, start + repl.length(), src.length - (start + oldLen));
        return out;
    }

    /** First index-range [start,end] of a run of >=minLen hex-digit bytes. */
    private static int[] firstHexSlice(byte[] b, int minLen) {
        int i = 0;
        while (i < b.length) {
            if (!isHex((char) b[i])) { i++; continue; }
            int end = i + 1;
            while (end < b.length && isHex((char) b[end])) end++;
            if (end - i >= minLen) return new int[]{i, end - 1};
            i = end;
        }
        return null;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // --- zip io ------------------------------------------------------------

    private static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                m.put(e.getName(), bos.toByteArray());
            }
        }
        return m;
    }

    private static void writeZip(Map<String, byte[]> m, Path p) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            for (Map.Entry<String, byte[]> e : m.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }
}

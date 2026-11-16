package studio.deimos.pinyonshift;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The VR camera mods on the headset, at every game start (Quest release).
 * The head-tracked cockpit and hood views drive the immersive camera mod's
 * head layers, so the game needs two mods:
 *
 * - immersive_camera (Pinyon Shift's own, BSD-3), packaged as
 *   assets/mods/immersive_camera and copied into state/mods;
 * - zz-archive-patches: the player's own media/camera.zip with the mod's
 *   layers merged into its CameraPhysics.xml, and zipmanifest.xml's line for
 *   it. That is the game's data, so it is built here from the player's disc
 *   files, exactly as tools/build-mod-archives.py builds it on a PC
 *   (XmlMerge, FhArchive; the LZX member through the runtime's decoder).
 *
 * Both are then listed in enabled_mods. Nothing is done when they are
 * already current, and a failure leaves the game to start without them.
 */
final class VrMods {
    private static final String TAG = "PinyonShift";
    private static final String MOD = "immersive_camera";
    private static final String GENERATED = "zz-archive-patches";
    private static final String ASSETS = "mods/" + MOD;
    private static final String MERGE = "merge/media/camera.zip/CameraPhysics.xml";
    private static final String ARCHIVE = "media/camera.zip";
    private static final String MEMBER = "cameraphysics.xml";
    private static final String MANIFEST = "media/zipmanifest.xml";
    private static final String BUILTIN_MARKER = ".pinyon-builtin";
    private static final String INPUTS = ".fhvr-inputs";
    private static final Pattern ENABLED_MODS =
            Pattern.compile("^enabled_mods[ \\t]*=[ \\t]*\"([^\"]*)\"[ \\t]*$", Pattern.MULTILINE);

    private static boolean libraryLoaded;

    private static native byte[] nativeDecompressXmem(byte[] compressed, int size);

    private VrMods() {}

    /** Installs, builds and enables the mods; never throws. */
    static void install(Context context, File base) {
        try {
            AssetManager assets = context.getAssets();
            String[] packaged = assets.list(ASSETS);
            if (packaged == null || packaged.length == 0) return;  // a PC build: the launcher does this
            File game = new File(base, "game/base");
            File archive = findCaseInsensitive(game, ARCHIVE);
            File manifest = findCaseInsensitive(game, MANIFEST);
            if (archive == null || manifest == null) return;  // no game files yet
            File mods = new File(base, "state/mods");
            if (!installBuiltin(assets, new File(mods, MOD))) return;
            buildArchivePatches(assets, game, archive, manifest, new File(mods, GENERATED));
            enable(new File(base, SetupActivity.CONFIG_FILE), new File(new File(mods, MOD), "mod.toml"));
        } catch (Exception | UnsatisfiedLinkError error) {
            Log.e(TAG, "VR camera mods not installed", error);
        }
    }

    // ---- immersive_camera ------------------------------------------------------

    // The packaged mod, copied where it is missing or differs; a player's own
    // mod of the same name (no marker) is kept. False when it is not there.
    private static boolean installBuiltin(AssetManager assets, File target) throws IOException {
        if (target.exists() && !new File(target, BUILTIN_MARKER).isFile()) {
            Log.w(TAG, "VR camera mods: state/mods/" + MOD + " is the player's own; kept");
            return new File(target, "mod.toml").isFile();
        }
        List<String> files = new ArrayList<>();
        list(assets, ASSETS, "", files);
        boolean current = target.isDirectory();
        for (String file : files) {
            if (!current) break;
            File installed = new File(target, file);
            current = installed.isFile() && installed.length() == readAsset(assets, ASSETS + "/" + file).length;
        }
        if (current) return true;
        File staging = new File(target.getPath() + ".installing");
        deleteTree(staging);
        for (String file : files) {
            write(new File(staging, file), readAsset(assets, ASSETS + "/" + file));
        }
        write(new File(staging, BUILTIN_MARKER),
                "Installed by ForzaHorizon-VR; replaced when the app updates.\n".getBytes(StandardCharsets.UTF_8));
        deleteTree(target);
        if (!staging.renameTo(target)) throw new IOException("cannot write " + target);
        Log.i(TAG, "VR camera mods: " + MOD + " installed (" + files.size() + " files)");
        return true;
    }

    private static void list(AssetManager assets, String root, String prefix, List<String> out) throws IOException {
        String[] names = assets.list(root + (prefix.isEmpty() ? "" : "/" + prefix));
        if (names == null) return;
        for (String name : names) {
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            String[] children = assets.list(root + "/" + path);
            if (children != null && children.length > 0) list(assets, root, path, out);
            else out.add(path);
        }
    }

    // ---- zz-archive-patches ------------------------------------------------------

    private static void buildArchivePatches(AssetManager assets, File game, File archive, File manifestFile,
                                            File target) throws IOException {
        byte[] data = read(archive);
        byte[] manifestBytes = read(manifestFile);
        byte[] patch = readAsset(assets, ASSETS + "/" + MERGE);
        String inputs = sha256(data) + " " + sha256(manifestBytes) + " " + sha256(patch) + "\n";
        File stamp = new File(target, INPUTS);
        if (stamp.isFile() && inputs.equals(new String(read(stamp), StandardCharsets.UTF_8))
                && new File(target, "game/" + relative(game, archive)).isFile()) {
            return;
        }
        long started = System.currentTimeMillis();
        String relativeArchive = relative(game, archive);
        String manifest = new String(manifestBytes, StandardCharsets.UTF_8);
        if (manifest.startsWith("\uFEFF")) manifest = manifest.substring(1);
        FhArchive.checkSupported(data, manifest, relativeArchive);
        byte[] baseXml = FhArchive.read(data, MEMBER, new FhArchive.Lzx() {
            @Override
            public byte[] decompress(byte[] compressed, int size) {
                loadLibrary();
                return nativeDecompressXmem(compressed, size);
            }
        });
        byte[] merged;
        try {
            merged = XmlMerge.merge(baseXml, patch);
        } catch (XmlMerge.XmlException error) {
            throw new IOException("CameraPhysics.xml: " + error.getMessage());
        }
        FhArchive.Rebuilt rebuilt = FhArchive.rebuild(data, Collections.singletonMap(MEMBER, merged));
        String newManifest = FhArchive.updateManifest(manifest, relativeArchive, rebuilt);

        File staging = new File(target.getPath() + ".building");
        deleteTree(staging);
        write(new File(staging, "game/" + relativeArchive), rebuilt.data);
        write(new File(staging, "game/" + relative(game, manifestFile)), XmlMerge.withBom(newManifest));
        write(new File(staging, "mod.toml"), ("# Generated by ForzaHorizon-VR from the player's own game files, "
                + "as tools/build-mod-archives.py does.\nname = \"" + GENERATED + "\"\nversion = \"1\"\nabi = 1\n"
                + "shares_save = true\n").getBytes(StandardCharsets.UTF_8));
        write(new File(staging, "archives.json"), ("[\n  {\n    \"archive\": \"" + relativeArchive + "\",\n"
                + "    \"members\": {},\n    \"merged\": {\n      \"" + MEMBER + "\": [\n        \"" + MOD + "\"\n"
                + "      ]\n    },\n    \"strings\": {},\n    \"added\": {},\n    \"base_sha256\": \"" + sha256(data)
                + "\",\n    \"sha256\": \"" + sha256(rebuilt.data) + "\"\n  }\n]\n").getBytes(StandardCharsets.UTF_8));
        write(new File(staging, INPUTS), inputs.getBytes(StandardCharsets.UTF_8));
        deleteTree(target);
        if (!staging.renameTo(target)) throw new IOException("cannot write " + target);
        Log.i(TAG, "VR camera mods: " + GENERATED + " built from the player's " + relativeArchive + " in "
                + (System.currentTimeMillis() - started) + " ms (sha256 " + sha256(rebuilt.data) + ")");
    }

    private static synchronized void loadLibrary() {
        if (libraryLoaded) return;
        System.loadLibrary("c++_shared");
        System.loadLibrary("rexruntime");
        System.loadLibrary("fhvr_archive");
        libraryLoaded = true;
    }

    // ---- enabled_mods ---------------------------------------------------------------

    // zz-archive-patches first, as the PC tools order it, then the camera
    // mod; other mods stay. Before the game's first start there is no
    // settings file: one with only enabled_mods is what the game expects
    // from the mod tools, and it adds its defaults to it. Enabling the mod
    // in an existing file adds its off_settings where the file has no such
    // key, as tools/build-mod-patches.py does.
    private static void enable(File config, File modToml) throws IOException {
        if (!config.isFile()) {
            write(config, ("enabled_mods = \"" + GENERATED + "," + MOD + "\"\n").getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "VR camera mods enabled in a new settings file");
            return;
        }
        String text = new String(read(config), StandardCharsets.UTF_8);
        Matcher match = ENABLED_MODS.matcher(text);
        List<String> mods = new ArrayList<>();
        if (match.find()) {
            for (String name : match.group(1).split(",")) {
                if (!name.trim().isEmpty()) mods.add(name.trim());
            }
        }
        List<String> wanted = new ArrayList<>();
        wanted.add(GENERATED);
        for (String name : mods) if (!name.equals(GENERATED)) wanted.add(name);
        if (!wanted.contains(MOD)) {
            wanted.add(MOD);
            String toml = modToml.isFile() ? new String(read(modToml), StandardCharsets.UTF_8) : "";
            Matcher off = Pattern.compile("^off_settings[ \\t]*=[ \\t]*\\[([^\\]]*)\\]", Pattern.MULTILINE).matcher(toml);
            if (off.find()) {
                Matcher line = Pattern.compile("\"([A-Za-z0-9_]+[ \\t]*=[^\"]*)\"").matcher(off.group(1));
                StringBuilder added = new StringBuilder();
                while (line.find()) {
                    String key = line.group(1).split("=", 2)[0].trim();
                    if (!Pattern.compile("^" + Pattern.quote(key) + "[ \\t]*=", Pattern.MULTILINE).matcher(text).find()) {
                        added.append(line.group(1)).append('\n');
                    }
                }
                if (added.length() > 0) text = text.replaceAll("\n+$", "") + "\n" + added;
            }
        }
        if (wanted.equals(mods)) return;
        write(config, text.getBytes(StandardCharsets.UTF_8));
        SetupActivity.applyEnabledMods(config, String.join(",", wanted));
        Log.i(TAG, "VR camera mods enabled: " + String.join(",", wanted));
    }

    // ---- Files ---------------------------------------------------------------------

    private static File findCaseInsensitive(File root, String relative) {
        File current = root;
        for (String part : relative.split("/")) {
            File[] children = current.listFiles();
            if (children == null) return null;
            File next = null;
            for (File child : children) {
                if (child.getName().toLowerCase(Locale.ROOT).equals(part.toLowerCase(Locale.ROOT))) next = child;
            }
            if (next == null) return null;
            current = next;
        }
        return current.isFile() ? current : null;
    }

    private static String relative(File root, File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String sha256(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                hex.append(String.format(Locale.ROOT, "%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

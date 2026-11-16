package studio.deimos.pinyonshift;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Where the game's own libraries come from.
 *
 * A package built on a PC carries libmain.so and the two facades beside the
 * runtime. The Quest release carries no game code: the first start builds
 * them on the device from the player's own disc files (BuildActivity) into
 * the app's internal storage, which the system lets the app load code from,
 * and the game loads them from there. The build is redone when the app is
 * updated (the runtime the libraries link against changes with it) or the
 * game's default.xex changes.
 */
final class GameBuild {
    static final String MAIN = "libmain.so";
    static final String[] LIBRARIES = {
        MAIN, "libpinyon_shift_SpeechFacade_default.so", "libpinyon_shift_XMediaFacade_default.so",
    };
    static final String KIT_ASSET = "buildkit.zip";

    private GameBuild() {}

    /** The package carries the game (a PC build): nothing to build. */
    static boolean packaged(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, MAIN).isFile();
    }

    /** The package carries the build kit (the Quest release). */
    static boolean hasKit(Context context) {
        try (InputStream in = context.getAssets().open(KIT_ASSET)) {
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    static File work(Context context) {
        return new File(context.getFilesDir(), "gamebuild");
    }

    static File output(Context context) {
        return new File(work(context), "out");
    }

    static File library(Context context, String name) {
        return packaged(context) ? new File(context.getApplicationInfo().nativeLibraryDir, name)
                                 : new File(output(context), name);
    }

    static long versionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            return 0;
        }
    }

    static final String BUILD_ID_ASSET = "game-build-id.txt";

    // assets/game-build-id.txt (tools/pinyon_android.py): "id <hash>" of what
    // the game's libraries are built and linked against (the runtime and the
    // build kit), and "compatible <version code>" lines for earlier app
    // versions whose builds used exactly the same, so an update that only
    // changes the app's screens does not rebuild the game.
    private static String[] buildId(Context context) {
        try (InputStream in = context.getAssets().open(BUILD_ID_ASSET)) {
            byte[] data = new byte[1 << 14];
            int length = 0;
            for (int read; length < data.length && (read = in.read(data, length, data.length - length)) > 0; ) {
                length += read;
            }
            return new String(data, 0, length, StandardCharsets.UTF_8).trim().split("\n");
        } catch (IOException error) {
            return new String[0];
        }
    }

    /** What a finished build was made from: this runtime and kit, and this disc. */
    static String expectedStamp(Context context, File gameBase) {
        String size = Long.toString(new File(gameBase, "default.xex").length());
        for (String line : buildId(context)) {
            if (line.startsWith("id ")) return line.trim() + " " + size;
        }
        return versionCode(context) + " " + size;
    }

    // A build an earlier app version stamped with its version code, whose
    // runtime and kit this version still has.
    private static boolean compatibleStamp(Context context, File gameBase, String stamp) {
        String[] fields = stamp.split(" ");
        if (fields.length != 2) return false;
        if (!fields[1].equals(Long.toString(new File(gameBase, "default.xex").length()))) return false;
        for (String line : buildId(context)) {
            if (line.trim().equals("compatible " + fields[0])) return true;
        }
        return false;
    }

    static File stampFile(Context context) {
        return new File(output(context), "build.stamp");
    }

    /** The game files are there but their libraries are not built yet. */
    static boolean needed(Context context, File gameBase) {
        if (packaged(context) || !hasKit(context)) return false;
        for (String name : LIBRARIES) {
            if (!new File(output(context), name).isFile()) return true;
        }
        String stamp = read(stampFile(context));
        if (expectedStamp(context, gameBase).equals(stamp)) return false;
        if (compatibleStamp(context, gameBase, stamp)) {
            try {
                writeStamp(context, gameBase);
            } catch (IOException error) {
                // Accepted again at the next start.
            }
            return false;
        }
        return true;
    }

    static void writeStamp(Context context, File gameBase) throws IOException {
        write(stampFile(context), expectedStamp(context, gameBase));
    }

    static String read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 1 << 16)];
            int length = 0;
            for (int read; length < data.length && (read = in.read(data, length, data.length - length)) > 0; ) {
                length += read;
            }
            return new String(data, 0, length, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return "";
        }
    }

    static void write(File file, String text) throws IOException {
        File partial = new File(file.getPath() + ".partial");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!partial.renameTo(file)) throw new IOException("could not write " + file);
    }
}

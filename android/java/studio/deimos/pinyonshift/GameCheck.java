package studio.deimos.pinyonshift;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks the player's game files before the headset builds the game and
 * before every start, so a disc the build can't use ends on a clear screen
 * instead of a game that crashes after building.
 *
 * The Quest release carries assets/game-files.txt (tools/pinyon_android.py
 * writes it from config/supported-extracted-ms-2505.json): the SHA-256 of
 * the three executables of the supported disc, Forza Horizon US (MS-2505),
 * and the size of every file extracted from it. Names, sizes and hashes
 * only, no game data.
 *
 * - Another release's executables: the translator's settings are made for
 *   the US disc's code, so the build would make a game that crashes. Blocks.
 * - Files missing or of another size: a copy that stopped or a damaged
 *   dump. The player may start anyway.
 * - Title update v4 copied from the PC: the headset builds the disc's game,
 *   which ignores the update but can't load a save played on v4. The player
 *   may set the update aside (moved, never deleted).
 */
final class GameCheck {
    private static final String TAG = "PinyonShift";
    static final String ASSET = "game-files.txt";
    static final String UPDATE_DIR = "state/title-update-v4";
    static final String UPDATE_ASIDE = "state/title-update-v4.off";
    // The 1000 Club arguments the setup screen adds with title update v4.
    private static final String[] CLUB_ARGUMENTS = {
        "--pinyon_shift_car_challenge_gate_probe=true", "--xam_report_live_signin=true",
    };

    enum Kind { WRONG_DISC, INCOMPLETE, TITLE_UPDATE }

    static final class Problem {
        final Kind kind;
        final String title;
        final String message;
        final String key;  // what the player accepted, for INCOMPLETE

        Problem(Kind kind, String title, String message, String key) {
            this.kind = kind;
            this.title = title;
            this.message = message;
            this.key = key;
        }

        /** The player may go on (else only the setup screen helps). */
        boolean canContinue() {
            return kind != Kind.WRONG_DISC;
        }
    }

    private GameCheck() {}

    /** The app's external files folder, which holds game/ and state/. */
    static File base(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    private static final class Expected {
        final Map<String, String> hashes = new HashMap<>();
        final Map<String, Long> sizes = new HashMap<>();
    }

    private static Expected expected(Context context) {
        try (InputStream in = context.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Expected expected = new Expected();
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split(" ", 3);
                if (fields.length != 3) continue;
                if (fields[0].equals("sha256")) expected.hashes.put(fields[2], fields[1].toUpperCase(Locale.ROOT));
                else if (fields[0].equals("size")) expected.sizes.put(fields[2], Long.parseLong(fields[1]));
            }
            return expected.hashes.isEmpty() ? null : expected;
        } catch (IOException | NumberFormatException error) {
            return null;  // a package built on a PC carries the game: nothing to check
        }
    }

    /** The first problem with the game files, or null when they are fine. */
    static Problem find(Context context) {
        Expected expected = expected(context);
        if (expected == null) return null;
        File base = base(context);
        File game = new File(base, "game/base");
        if (!new File(game, "default.xex").isFile()) return null;  // the setup screen's job

        // 1. The executables: the US disc's, byte for byte.
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.hashes.entrySet()) {
            File file = new File(game, entry.getKey());
            if (!file.isFile()) {
                wrong.add(entry.getKey() + " is missing");
            } else if (!entry.getValue().equals(cachedHash(context, file, expected.sizes.get(entry.getKey())))) {
                wrong.add(entry.getKey() + " is different");
            }
        }
        if (!wrong.isEmpty()) {
            Log.w(TAG, "game check: not the supported disc: " + wrong);
            return new Problem(Kind.WRONG_DISC, "These game files are not from the supported disc",
                    "ForzaHorizon-VR can only build the game from Forza Horizon for Xbox 360, US release "
                    + "(MS-2505, the cover says \"NTSC\" / \"USA\"). Your files are from another release or "
                    + "were changed (" + String.join(", ", wrong) + "). Other releases contain different "
                    + "program code, so the game built from them would crash.\n\n"
                    + "Open the setup screen and copy the game files extracted from a US disc. Your saves "
                    + "and settings stay as they are.", null);
        }

        // 2. Title update v4.
        if (new File(base, UPDATE_DIR + "/default.xexp").isFile()) {
            return new Problem(Kind.TITLE_UPDATE, "Title update v4 is not supported on the headset",
                    "You copied title update v4 from your PC. The headset builds the game from your disc "
                    + "alone, which does not use the title update, and it cannot load a save that was played "
                    + "with title update v4 on the PC.\n\n"
                    + "\"Continue without the title update\" moves it aside (to " + UPDATE_ASIDE
                    + ", nothing is deleted). If the save you copied from the PC was played with title update "
                    + "v4, the game cannot load it: start a new career, or copy a save from the base game.",
                    null);
        }

        // 3. Every other file there, at the disc's size.
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Long> entry : expected.sizes.entrySet()) {
            if (expected.hashes.containsKey(entry.getKey())) continue;
            File file = new File(game, entry.getKey());
            if (file.length() != entry.getValue()) missing.add(entry.getKey());
        }
        if (!missing.isEmpty()) {
            java.util.Collections.sort(missing);
            String key = missing.size() + " " + missing.get(0);
            if (key.equals(GameBuild.read(ackFile(context)))) return null;
            Log.w(TAG, "game check: " + missing.size() + " files missing or of another size, first "
                    + missing.get(0));
            StringBuilder examples = new StringBuilder();
            for (int i = 0; i < Math.min(3, missing.size()); i++) {
                examples.append(i > 0 ? ", " : "").append(missing.get(i));
            }
            return new Problem(Kind.INCOMPLETE, "Some game files are missing",
                    missing.size() + " of the " + expected.sizes.size() + " files of the disc are missing "
                    + "or have the wrong size (for example " + examples + "). The copy probably stopped "
                    + "before it was done. The game may not start, or stop when it needs those files.\n\n"
                    + "Open the setup screen and copy the game again: it only copies what is missing. Or "
                    + "start anyway.", key);
        }
        return null;
    }

    /** The player's answer to a problem that allows going on. */
    static void resolve(Context context, Problem problem) throws IOException {
        File base = base(context);
        if (problem.kind == Kind.TITLE_UPDATE) {
            File update = new File(base, UPDATE_DIR);
            File aside = new File(base, UPDATE_ASIDE);
            for (int n = 1; aside.exists(); n++) aside = new File(base, UPDATE_ASIDE + "-" + n);
            if (!update.renameTo(aside)) throw new IOException("could not move " + update);
            Log.i(TAG, "game check: title update v4 moved to " + aside);
            removeArguments(new File(base, SetupActivity.ARGUMENTS_FILE), CLUB_ARGUMENTS);
        } else if (problem.kind == Kind.INCOMPLETE) {
            GameBuild.write(ackFile(context), problem.key);
        }
    }

    private static File ackFile(Context context) {
        return new File(context.getFilesDir(), "game-check-accepted.txt");
    }

    private static void removeArguments(File file, String[] flags) throws IOException {
        if (!file.isFile()) return;
        StringBuilder kept = new StringBuilder();
        for (String line : GameBuild.read(file).split("\n")) {
            boolean drop = false;
            for (String flag : flags) drop |= line.trim().equals(flag);
            if (!drop) kept.append(line).append('\n');
        }
        GameBuild.write(file, kept.toString());
    }

    // ---- Hashes ---------------------------------------------------------

    // SHA-256 of the file, remembered by size and time so later starts only
    // read the file again when it changed. A file of another size than the
    // disc's is not read at all.
    private static String cachedHash(Context context, File file, Long expectedSize) {
        if (expectedSize != null && file.length() != expectedSize) return "size " + file.length();
        File cache = new File(context.getFilesDir(), "game-check-" + file.getName() + ".txt");
        String stamp = file.length() + " " + file.lastModified();
        String[] cached = GameBuild.read(cache).split(" ");
        if (cached.length == 3 && (cached[0] + " " + cached[1]).equals(stamp)) return cached[2];
        String hash = sha256(file);
        if (hash != null) {
            try {
                GameBuild.write(cache, stamp + " " + hash);
            } catch (IOException error) {
                Log.w(TAG, "game check: hash not cached", error);
            }
        }
        return hash;
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02X", b));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException error) {
            Log.w(TAG, "game check: could not read " + file, error);
            return null;
        }
    }
}

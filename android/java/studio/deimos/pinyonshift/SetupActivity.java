package studio.deimos.pinyonshift;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's entry point. With the game files in place it starts the game at
 * once. Without them it gets them from the player's own disc, on the
 * headset itself:
 *
 * - from the disc image (.iso) the player made of their disc and copied to
 *   the headset, read directly through the system's file picker
 *   (XisoReader), or
 * - from a folder holding the files already extracted from it.
 *
 * Files go where tools/pinyon.py android push-data puts them: the game in
 * files/game/base. A copy that stops resumes where it was when the same
 * image or folder is chosen again. The image's executables are checked
 * against the supported disc (GameCheck) before the other files are copied.
 */
public class SetupActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "PinyonShiftSetup";
    private static final int REQUEST_FOLDER = 0x5055;
    private static final int REQUEST_IMAGE = 0x5056;
    private static final int ACCENT = Color.rgb(241, 174, 54);
    private static final int MUTED = Color.rgb(159, 178, 165);
    // Game files being copied: the game does not start from a partial copy.
    private static final String INCOMPLETE = "game/.copy-incomplete";
    // Arguments the game activity adds at every start (one per line).
    static final String ARGUMENTS_FILE = "state/config/android-arguments.txt";
    // The app shortcut opens this screen with the game installed.
    private static final String SETUP_ACTION = "studio.deimos.pinyonshift.SETUP";
    // The game's settings, where enabled_mods lives.
    static final String CONFIG_FILE = "state/config/pinyon_shift.toml";
    // enabled_mods to set once the game has written its settings file,
    // applied by the game activity.
    static final String PENDING_MODS_FILE = "state/config/pending-enabled-mods.txt";
    // A disc image's executables, unpacked aside to be checked.
    private static final String STAGING = "game/.image-check";
    // The port's page on the website.
    private static final String WEBSITE = "https://portroyale.online/forzahorizon.html";
    // The disc's system update folder: not part of the game.
    private static final String SYSTEM_UPDATE = "$SystemUpdate";
    // Free space kept on top of the copy.
    private static final long SPARE = 256L << 20;

    private File base;
    private TextView intro;
    private ProgressBar progress;
    private TextView progressText;
    private Button imageButton;
    private Button folderButton;
    private Button deleteImageButton;
    private Button playButton;
    private Uri importedImage;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File external = getExternalFilesDir(null);
        base = external != null ? external : getFilesDir();
        Intent intent = getIntent();
        boolean asked = intent != null && (SETUP_ACTION.equals(intent.getAction())
                || intent.getBooleanExtra("setup", false));
        if (gameReady() && !asked) {
            startGame();
            return;
        }
        buildLayout();
        refresh();
    }

    @Override
    protected void onDestroy() {
        VrShell.detach(this);
        super.onDestroy();
    }

    private boolean gameReady() {
        return new File(base, "game/base/default.xex").isFile() && !new File(base, INCOMPLETE).exists();
    }

    private void startGame() {
        // The Quest release builds the game on the device first (GameBuild).
        boolean build = GameBuild.needed(this, new File(base, "game/base"));
        Intent game = new Intent(this, build ? BuildActivity.class : PinyonShiftActivity.class);
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            game.putExtras(intent.getExtras());
            game.removeExtra("setup");
        }
        startActivity(game);
        finish();
    }

    // ---- Layout -------------------------------------------------------

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private TextView text(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(view, params);
        return view;
    }

    private Button button(LinearLayout parent, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private void buildLayout() {
        getWindow().setStatusBarColor(Color.rgb(15, 26, 20));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(15, 26, 20));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(24), dp(28), dp(32));
        scroll.addView(column);

        text(column, "ForzaHorizon-VR", 26, Color.WHITE, true);
        // The port's page: install guide, updates and help.
        TextView site = text(column, "portroyale.online: guide, updates and help", 15, ACCENT, false);
        site.setPaintFlags(site.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        site.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)));
                } catch (Exception error) {
                    progressText.setText("No browser found. The page is " + WEBSITE);
                }
            }
        });
        intro = text(column, "", 15, MUTED, false);

        text(column, "From your disc image (.iso)", 18, ACCENT, true).setPadding(0, dp(16), 0, 0);
        text(column, "Copy the .iso file you made from your own Forza Horizon disc (Xbox 360, US release) "
                + "to the headset: connect it to your PC with a USB cable, allow file access in the "
                + "headset, and copy the file into the Download folder. Then choose it here. The game "
                + "files are copied out of it into the app (about 7 GB); you can delete the .iso "
                + "afterwards.", 14, MUTED, false);
        imageButton = button(column, "Choose the .iso file…");

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(12);
        column.addView(progress, barParams);
        progressText = text(column, "", 13, Color.WHITE, false);
        deleteImageButton = button(column, "Delete the .iso file");
        deleteImageButton.setVisibility(View.GONE);

        text(column, "From a folder", 18, ACCENT, true).setPadding(0, dp(20), 0, 0);
        text(column, "Or, if you already extracted the disc (the folder holding default.xex), copy that "
                + "folder to the headset and choose it here.", 14, MUTED, false);
        folderButton = button(column, "Choose folder…");


        playButton = button(column, "Play");
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ((LinearLayout.LayoutParams) playButton.getLayoutParams()).topMargin = dp(24);
        // On a VR panel (VrShell) on Meta Quest and PICO.
        VrShell.attach(this, scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VrShell.resume(this);
    }

    @Override
    protected void onPause() {
        VrShell.pause(this);
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        if (view == imageButton) choose(Intent.ACTION_OPEN_DOCUMENT, REQUEST_IMAGE);
        else if (view == folderButton) choose(Intent.ACTION_OPEN_DOCUMENT_TREE, REQUEST_FOLDER);
        else if (view == deleteImageButton) deleteImage();
        else if (view == playButton) startGame();
    }

    private void refresh() {
        boolean ready = gameReady();
        intro.setText(ready
                ? "The game is installed. To copy it again from your disc image or folder, choose it below: "
                  + "only missing or damaged files are copied."
                : "The game files are not on this device yet. They come from your own Forza Horizon disc "
                  + "(Xbox 360, US release), as a disc image (.iso) or an extracted folder. About 7 GB is "
                  + "needed for the game, plus about 4 GB while the headset builds it the first time.");
        playButton.setEnabled(ready && !busy);
        imageButton.setEnabled(!busy);
        folderButton.setEnabled(!busy);
        deleteImageButton.setEnabled(!busy);
    }

    // ---- Choosing the source ------------------------------------------------

    private void choose(String action, int request) {
        Intent intent = new Intent(action);
        if (request == REQUEST_IMAGE) {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // .iso files come with all kinds of types (or none): show every file.
            intent.setType("*/*");
        }
        try {
            startActivityForResult(intent, request);
        } catch (Exception error) {
            progressText.setText("This device has no file picker.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_FOLDER && requestCode != REQUEST_IMAGE) || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri chosen = data.getData();
        final boolean image = requestCode == REQUEST_IMAGE;
        busy = true;
        refresh();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        deleteImageButton.setVisibility(View.GONE);
        progressText.setText(image ? "Reading the disc image…" : "Reading the folder…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (image) {
                        importImage(chosen);
                        importedImage = chosen;
                        done("Done. You can delete the .iso file now to free space, then press Play.", true, true);
                    } else {
                        importFolder(chosen);
                        done("Done. Press Play.", true, false);
                    }
                } catch (IOException error) {
                    Log.e(TAG, (image ? "image" : "folder") + " import failed", error);
                    done("The copy stopped: " + error.getMessage() + ". Choose the "
                            + (image ? ".iso file" : "folder") + " again to continue.", false, false);
                }
            }
        }, image ? "ImageImport" : "FolderImport").start();
    }

    // The end of a copy, from the worker thread.
    private void done(final String message, final boolean success, final boolean offerDelete) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (success) progress.setProgress(progress.getMax());
                else progress.setVisibility(View.GONE);
                progressText.setText(message);
                deleteImageButton.setVisibility(offerDelete ? View.VISIBLE : View.GONE);
                refresh();
            }
        });
    }

    private void deleteImage() {
        if (importedImage == null) return;
        try {
            if (DocumentsContract.deleteDocument(getContentResolver(), importedImage)) {
                progressText.setText("The .iso file was deleted. Press Play.");
                deleteImageButton.setVisibility(View.GONE);
                importedImage = null;
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "image not deleted", error);
        }
        progressText.setText("The .iso file could not be deleted here. Delete it with the headset's Files app "
                + "or from your PC.");
    }

    // ---- Copy from a disc image ------------------------------------------

    private void importImage(Uri uri) throws IOException {
        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("cannot open the file");
            try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
                 FileChannel channel = in.getChannel()) {
                XisoReader disc = XisoReader.open(channel);
                List<XisoReader.Entry> files = disc.files(Collections.singleton(SYSTEM_UPDATE));
                List<XisoReader.Entry> executables = new ArrayList<>();
                List<XisoReader.Entry> rest = new ArrayList<>();
                for (XisoReader.Entry entry : files) {
                    boolean executable = !entry.path.contains("/") && entry.path.endsWith(".xex");
                    (executable ? executables : rest).add(entry);
                }
                // The executables first, into a folder aside: a disc the game
                // can't be built from is refused after seconds, and before it
                // replaces anything of a game already on the device.
                File staging = new File(base, STAGING);
                deleteTree(staging);
                staging.mkdirs();
                try {
                    for (XisoReader.Entry entry : executables) {
                        progressLater("Checking " + entry.path + "…");
                        disc.extract(entry, new File(staging, entry.path), null);
                    }
                    if (!new File(staging, "default.xex").isFile()) {
                        throw new IOException("this disc image is not an Xbox 360 game (no default.xex)");
                    }
                    List<String> wrong = GameCheck.wrongExecutables(this, staging);
                    if (wrong != null && !wrong.isEmpty()) {
                        Log.w(TAG, "disc image refused: " + wrong);
                        throw new IOException("this is not the supported disc. ForzaHorizon-VR needs Forza Horizon "
                                + "for Xbox 360, US release (MS-2505); other games and releases contain "
                                + "different program code. Nothing on this device was changed");
                    }
                    long total = 0, missing = 0;
                    for (XisoReader.Entry entry : files) {
                        total += entry.size;
                        if (destination("game/base/" + entry.path).length() != entry.size) missing += entry.size;
                    }
                    if (missing > base.getUsableSpace() - SPARE) {
                        throw new IOException("not enough free space: " + size(missing) + " needed, "
                                + size(base.getUsableSpace()) + " free");
                    }
                    markIncomplete();
                    for (XisoReader.Entry entry : executables) {
                        File target = destination("game/base/" + entry.path);
                        target.getParentFile().mkdirs();
                        replace(new File(staging, entry.path), target, entry.size, entry.path);
                    }
                    long done = 0;
                    for (XisoReader.Entry entry : executables) done += entry.size;
                    final long started = System.currentTimeMillis();
                    for (int i = 0; i < rest.size(); i++) {
                        final XisoReader.Entry entry = rest.get(i);
                        File target = destination("game/base/" + entry.path);
                        if (target.length() != entry.size) {
                            File part = new File(target.getPath() + ".part");
                            target.getParentFile().mkdirs();
                            final long before = done;
                            final int number = executables.size() + i + 1;
                            final int count = files.size();
                            final long sum = total;
                            disc.extract(entry, part, new XisoReader.Progress() {
                                @Override
                                public void written(long bytes) {
                                    report(before + bytes, sum, number, count, started);
                                }
                            });
                            replace(part, target, entry.size, entry.path);
                        }
                        done += entry.size;
                    }
                    new File(base, INCOMPLETE).delete();
                } finally {
                    deleteTree(staging);
                }
            }
        }
    }

    private void progressLater(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressText.setText(text);
            }
        });
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    // ---- Copy from a folder -----------------------------------------------

    private static final class Entry {
        final Uri uri;
        final String path;
        final long size;

        Entry(Uri uri, String path, long size) {
            this.uri = uri;
            this.path = path;
            this.size = size;
        }
    }

    private void importFolder(Uri tree) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        List<Entry> files = new ArrayList<>();
        list(tree, root, "", files);
        boolean game = false;
        long total = 0;
        for (Entry entry : files) {
            game |= entry.path.equals("default.xex");
            total += entry.size;
        }
        if (!game) {
            throw new IOException("that folder holds no default.xex; choose the extracted game folder itself");
        }
        if (total > base.getUsableSpace() - SPARE) {
            throw new IOException("not enough free space: " + size(total) + " needed");
        }
        markIncomplete();
        long done = 0;
        final long started = System.currentTimeMillis();
        final int count = files.size();
        for (int i = 0; i < files.size(); i++) {
            Entry entry = files.get(i);
            File target = destination("game/base/" + entry.path);
            if (target.length() != entry.size) {
                File part = new File(target.getPath() + ".part");
                target.getParentFile().mkdirs();
                final long before = done;
                final int number = i + 1;
                final long sum = total;
                try (InputStream in = getContentResolver().openInputStream(entry.uri);
                     OutputStream out = new FileOutputStream(part)) {
                    if (in == null) throw new IOException("cannot read " + entry.path);
                    copy(in, out, new Progress() {
                        @Override
                        public void written(long bytes) {
                            report(before + bytes, sum, number, count, started);
                        }
                    });
                }
                replace(part, target, entry.size, entry.path);
            }
            done += entry.size;
        }
        new File(base, INCOMPLETE).delete();
    }

    private void list(Uri tree, String document, String prefix, List<Entry> out) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document);
        try (Cursor cursor = getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) throw new IOException("cannot list the folder");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                        || name.contains("/")) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    list(tree, id, prefix + name + "/", out);
                } else {
                    out.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), prefix + name,
                            cursor.getLong(3)));
                }
            }
        }
    }

    // ---- Shared -----------------------------------------------------------

    private void markIncomplete() throws IOException {
        File incomplete = new File(base, INCOMPLETE);
        incomplete.getParentFile().mkdirs();
        new FileOutputStream(incomplete).close();
    }

    private static void replace(File part, File target, long size, String path) throws IOException {
        if (part.length() != size) throw new IOException("incomplete copy of " + path);
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
        if (!part.renameTo(target)) throw new IOException("cannot write " + target);
    }

    // A path inside the app's folder: the game or the state.
    private File destination(String path) throws IOException {
        if (!(path.startsWith("game/base/") || path.startsWith("state/")) || path.contains("\\")) {
            throw new IOException("unexpected path " + path);
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("unexpected path " + path);
            }
        }
        File target = new File(base, path);
        if (!target.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
            throw new IOException("unexpected path " + path);
        }
        return target;
    }

    private long lastReport;

    private void report(long done, long total, int number, int count, long started) {
        long now = System.currentTimeMillis();
        if (now - lastReport < 250) return;
        lastReport = now;
        double seconds = Math.max(1, (now - started) / 1000.0);
        final String line = size(done) + " of " + size(total) + " · file " + number + " of " + count
                + " · " + size((long) (done / seconds)) + "/s";
        final int value = total > 0 ? (int) (done * 1000 / total) : 0;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progress.setProgress(value);
                progressText.setText(line);
            }
        });
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.0f KB", Math.max(1, bytes / 1024.0));
    }

    interface Progress {
        void written(long bytes);
    }

    private static void copy(InputStream in, OutputStream out, Progress progress) throws IOException {
        byte[] buffer = new byte[1 << 17];
        long written = 0;
        for (int read; (read = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, read);
            written += read;
            if (progress != null) progress.written(written);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, null);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeAtomically(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        File temp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
        if (!temp.renameTo(file)) throw new IOException("cannot write " + file);
    }

    // ---- Mods ---------------------------------------------------------------

    private static final Pattern ENABLED_MODS =
            Pattern.compile("^enabled_mods[ \\t]*=[ \\t]*\"([^\"]*)\"[ \\t]*$", Pattern.MULTILINE);

    // Sets enabled_mods in the game's settings file; called by the game
    // activity for a list left in PENDING_MODS_FILE.
    static void applyEnabledMods(File config, String mods) throws IOException {
        String text;
        try (InputStream in = new FileInputStream(config)) {
            text = readAll(in);
        }
        String line = "enabled_mods = \"" + mods + "\"";
        Matcher match = ENABLED_MODS.matcher(text);
        if (match.find()) {
            text = text.substring(0, match.start()) + line + text.substring(match.end());
        } else if (!mods.isEmpty()) {
            text = text.replaceAll("\n+$", "") + (text.isEmpty() ? "" : "\n") + line + "\n";
        } else {
            return;
        }
        writeAtomically(config, text);
    }
}

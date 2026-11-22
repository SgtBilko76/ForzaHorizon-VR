package studio.deimos.pinyonshift;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's entry point (ONE_CLICK_SETUP_BACKLOG A-4). With the game files
 * in place it starts the game at once. Without them, or when opened from
 * the app icon's "Game files" shortcut, it copies the extracted game folder
 * (the one holding default.xex, copied to the device over USB) into
 * files/game/base through the system's folder picker. A copy that stops
 * resumes where it was.
 *
 * The copy from the PC over the local network and the experimental option
 * for GPUs without vertex shader stores (Mali phones) are gone: every
 * supported headset has an Adreno GPU, and players install from their own
 * disc's files.
 */
public class SetupActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "PinyonShiftSetup";
    private static final int REQUEST_FOLDER = 0x5055;
    private static final int ACCENT = Color.rgb(241, 174, 54);
    private static final int MUTED = Color.rgb(159, 178, 165);
    // Game files being copied: the game does not start from a partial copy.
    private static final String INCOMPLETE = "game/.copy-incomplete";
    // Arguments the game activity adds at every start.
    static final String ARGUMENTS_FILE = "state/config/android-arguments.txt";
    // The app shortcut opens this screen with the game installed.
    private static final String SETUP_ACTION = "studio.deimos.pinyonshift.SETUP";
    // The game's settings, where enabled_mods lives.
    static final String CONFIG_FILE = "state/config/pinyon_shift.toml";
    // enabled_mods to set once the game has written its settings file,
    // applied by the game activity.
    static final String PENDING_MODS_FILE = "state/config/pending-enabled-mods.txt";

    private File base;
    private TextView intro;
    private ProgressBar progress;
    private TextView progressText;
    private Button folderButton;
    private Button playButton;
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
        intro = text(column, "", 15, MUTED, false);

        text(column, "Game folder", 18, ACCENT, true).setPadding(0, dp(20), 0, 0);
        text(column, "Copy the game folder extracted from your own disc (the one holding default.xex) to "
                + "this device over USB, for example into Download, and choose it here. It is copied "
                + "into the app; you can delete the copy afterwards.",
                14, MUTED, false);
        folderButton = button(column, "Choose folder…");
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(12);
        column.addView(progress, barParams);
        progressText = text(column, "", 13, Color.WHITE, false);

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
        if (view == folderButton) chooseFolder();
        else if (view == playButton) startGame();
    }

    private void refresh() {
        boolean ready = gameReady();
        intro.setText(ready
                ? "The game is installed. Choose a folder here to copy the game files again."
                : "The game files are not on this device yet. About 8 GB is needed.");
        playButton.setEnabled(ready && !busy);
        folderButton.setEnabled(!busy);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, null);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.0f KB", Math.max(1, bytes / 1024.0));
    }

    // The end of a copy, on a worker thread.
    private void finish(final String message, final boolean success) {
        busy = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (success) progress.setProgress(progress.getMax());
                progressText.setText(message);
                refresh();
            }
        });
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


    private static final Pattern ENABLED_MODS =
            Pattern.compile("^enabled_mods[ \\t]*=[ \\t]*\"([^\"]*)\"[ \\t]*$", Pattern.MULTILINE);

    // Also called by the game activity for a pending list.
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

    // ---- Copy from a folder -----------------------------------------

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (Exception error) {
            progressText.setText("This device has no folder picker.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri tree = data.getData();
        busy = true;
        refresh();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    importFolder(tree);
                    finish("Done. Press Play.", true);
                } catch (IOException error) {
                    Log.e(TAG, "folder import failed", error);
                    finish("The copy stopped: " + error.getMessage() + ". Choose the folder again to continue.",
                            false);
                }
            }
        }, "FolderImport").start();
    }

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
        if (total > base.getUsableSpace() - (256L << 20)) {
            throw new IOException("not enough free space: " + size(total) + " needed");
        }
        File incomplete = new File(base, INCOMPLETE);
        incomplete.getParentFile().mkdirs();
        new FileOutputStream(incomplete).close();
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
                if (part.length() != entry.size) throw new IOException("incomplete copy of " + entry.path);
                if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
                if (!part.renameTo(target)) throw new IOException("cannot write " + target);
            }
            done += entry.size;
        }
        incomplete.delete();
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
}
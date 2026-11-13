package studio.deimos.pinyonshift;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The first start of the Quest release: builds the game's libraries on the
 * device from the player's own disc files (GameBuild). The packaged build
 * kit is unpacked into internal storage, then its build.sh translates the
 * game (rexglue), compiles the translation (clang) and links it (lld), with
 * the tools packaged as native libraries so the app may run them. build.sh
 * reports its stage in status.txt and skips finished work, so a build that
 * was stopped (the headset slept, the app was closed) goes on where it
 * stopped. The game starts when it is done.
 */
public class BuildActivity extends Activity {
    private static final String TAG = "PinyonShift";
    private static final int ACCENT = Color.rgb(241, 174, 54);
    private static final int MUTED = Color.rgb(159, 178, 165);
    private static final int JOBS = 4;
    // Measured on a Quest 3: translating about 10 minutes, compiling about 13.
    private static final int TRANSLATE_SECONDS = 650;
    private static final int COMPILE_SECONDS = 800;

    // One build per process, whatever happens to the activity showing it.
    private static Thread runner;
    private static volatile String failure;
    private static volatile boolean finished;
    private static volatile long unpackDone, unpackTotal;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };
    static final Runnable EXIT = new Runnable() {
        @Override
        public void run() {
            System.exit(0);
        }
    };
    private File gameBase;
    private TextView stageText, detailText, errorText;
    private ProgressBar progress;
    private Button retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File external = getExternalFilesDir(null);
        File base = external != null ? external : getFilesDir();
        gameBase = new File(base, "game/base");
        buildLayout();
        start();
        handler.post(refresher);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

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
        params.topMargin = dp(10);
        parent.addView(view, params);
        return view;
    }

    private void buildLayout() {
        getWindow().setStatusBarColor(Color.rgb(15, 26, 20));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        column.setBackgroundColor(Color.rgb(15, 26, 20));
        column.setPadding(dp(40), dp(32), dp(40), dp(32));
        text(column, "ForzaHorizon-VR", 26, Color.WHITE, true);
        text(column, "Building the game for your headset from your own disc files. This happens once "
                + "after installing or updating and takes about 25 to 35 minutes. Leave the app open; "
                + "if it stops, it continues where it stopped the next time you start it.",
                15, MUTED, false);
        stageText = text(column, "", 18, ACCENT, true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        params.topMargin = dp(12);
        column.addView(progress, params);
        detailText = text(column, "", 14, Color.WHITE, false);
        errorText = text(column, "", 13, ACCENT, false);
        errorText.setVisibility(View.GONE);
        retryButton = new Button(this);
        retryButton.setText("Try again");
        retryButton.setAllCaps(false);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                failure = null;
                errorText.setVisibility(View.GONE);
                retryButton.setVisibility(View.GONE);
                start();
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(12);
        column.addView(retryButton, buttonParams);
        setContentView(column);
    }

    private void start() {
        synchronized (BuildActivity.class) {
            if (runner != null && runner.isAlive()) return;
            finished = false;
            failure = null;
            final android.content.Context context = getApplicationContext();
            final File game = gameBase;
            runner = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BuildActivity.run(context, game);
                        finished = true;
                    } catch (Exception error) {
                        Log.e(TAG, "game build failed", error);
                        failure = error.getMessage() != null ? error.getMessage() : error.toString();
                    }
                }
            }, "GameBuild");
            runner.start();
        }
    }

    // ---- The build ------------------------------------------------------

    private static void run(android.content.Context context, File game) throws IOException,
            InterruptedException {
        File work = GameBuild.work(context);
        File kit = new File(work, "kit");
        String version = Long.toString(GameBuild.versionCode(context));
        // Objects from another app version were made against another runtime.
        if (!version.equals(GameBuild.read(new File(work, "version")))) {
            deleteTree(work);
        }
        work.mkdirs();
        GameBuild.write(new File(work, "version"), version);
        if (!new File(kit, ".unpacked").isFile()) {
            unpackKit(context, kit);
        }
        File log = new File(work, "build_run.txt");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh",
                new File(kit, "build.sh").getAbsolutePath(), Integer.toString(JOBS));
        builder.environment().put("W", work.getAbsolutePath());
        builder.environment().put("K", kit.getAbsolutePath());
        builder.environment().put("T", context.getApplicationInfo().nativeLibraryDir);
        builder.environment().put("GAME", game.getAbsolutePath());
        builder.environment().put("HOME", work.getAbsolutePath());
        builder.environment().put("TMPDIR", new File(work, "tmp").getAbsolutePath());
        new File(work, "tmp").mkdirs();
        builder.redirectErrorStream(true);
        builder.redirectOutput(log);
        Process process = builder.start();
        int status = process.waitFor();
        if (status != 0 || !"done".equals(stage(work))) {
            throw new IOException("the build stopped (" + status + "): " + lastLines(log, 6));
        }
        // Loaded code must be read-only (Android 14).
        for (String name : GameBuild.LIBRARIES) {
            File library = new File(GameBuild.output(context), name);
            if (!library.isFile()) throw new IOException(name + " was not built");
            library.setWritable(false, false);
        }
        GameBuild.writeStamp(context, game);
    }

    private static void unpackKit(android.content.Context context, File kit) throws IOException {
        deleteTree(kit);
        kit.mkdirs();
        unpackDone = 0;
        unpackTotal = 1;
        try (InputStream in = context.getAssets().open(GameBuild.KIT_ASSET)) {
            unpackTotal = Math.max(1, in.available());
        } catch (IOException ignored) {
        }
        String root = kit.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[1 << 16];
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(GameBuild.KIT_ASSET))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                File target = new File(kit, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) {
                    throw new IOException("bad entry in the build kit: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                target.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(target)) {
                    for (int read; (read = zip.read(buffer)) > 0; ) {
                        out.write(buffer, 0, read);
                        unpackDone += read;
                    }
                }
            }
        }
        GameBuild.write(new File(kit, ".unpacked"), "1");
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.setWritable(true, false);
        file.delete();
    }

    private static String stage(File work) {
        String[] fields = GameBuild.read(new File(work, "status.txt")).split("\\s+");
        return fields.length > 0 ? fields[0] : "";
    }

    private static String lastLines(File file, int count) {
        String[] lines = GameBuild.read(file).split("\n");
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; ++i) {
            text.append(lines[i].trim()).append('\n');
        }
        return text.toString().trim();
    }

    // ---- Progress -------------------------------------------------------

    private void refresh() {
        if (finished) {
            handler.removeCallbacksAndMessages(null);
            startGame();
            return;
        }
        File work = GameBuild.work(this);
        String[] fields = GameBuild.read(new File(work, "status.txt")).split("\\s+");
        String stage = fields.length > 0 ? fields[0] : "";
        int done = fields.length > 1 ? parse(fields[1]) : 0;
        int total = fields.length > 2 ? Math.max(1, parse(fields[2])) : 1;
        int seconds = fields.length > 3 ? parse(fields[3]) : 0;
        boolean unpacking = !new File(work, "kit/.unpacked").isFile();
        double value;
        String label, detail;
        if (unpacking) {
            value = 0.03 * Math.min(1.0, (double) unpackDone / Math.max(1, unpackTotal * 3));
            label = "Preparing the build tools";
            detail = String.format(Locale.ROOT, "%d MB unpacked", unpackDone >> 20);
        } else if (stage.equals("translate") || stage.isEmpty()) {
            double part = Math.min(0.95, (double) seconds / TRANSLATE_SECONDS);
            value = 0.03 + 0.40 * part;
            label = "Step 1 of 3: translating the game code";
            detail = minutes(Math.max(60, TRANSLATE_SECONDS - seconds) + COMPILE_SECONDS) + " left";
        } else if (stage.equals("pch")) {
            value = 0.43 + 0.03 * done / total;
            label = "Step 2 of 3: compiling";
            detail = "Preparing headers";
        } else if (stage.equals("compile")) {
            value = 0.46 + 0.50 * done / total;
            label = "Step 2 of 3: compiling";
            int left = done > 0 ? seconds * (total - done) / done : COMPILE_SECONDS;
            detail = String.format(Locale.ROOT, "%d of %d files, %s left", done, total,
                    minutes(left + 30));
        } else if (stage.equals("link") || stage.equals("done")) {
            value = 0.96 + 0.04 * done / total;
            label = "Step 3 of 3: linking";
            detail = "Almost done";
        } else {
            value = 0;
            label = "The build stopped";
            detail = "";
        }
        stageText.setText(label);
        detailText.setText(detail);
        progress.setProgress((int) Math.round(value * 1000));
        String error = failure;
        if (error != null) {
            stageText.setText("The build stopped");
            errorText.setText(error);
            errorText.setVisibility(View.VISIBLE);
            retryButton.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(refresher, 1000);
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String minutes(int seconds) {
        int minutes = Math.max(1, (seconds + 59) / 60);
        return minutes == 1 ? "about 1 minute" : "about " + minutes + " minutes";
    }

    private void startGame() {
        Intent game = new Intent(this, PinyonShiftActivity.class);
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) game.putExtras(intent.getExtras());
        startActivity(game);
        finish();
        // This is the build's own process (android:process=":build"); the
        // game runs in the app's main process.
        new Handler(Looper.getMainLooper()).postDelayed(EXIT, 1500);
    }
}

package com.moutrancorp.memspike;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private Button extractButton;
    private ProgressBar progress;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF8F7F4);

        TextView title = new TextView(this);
        title.setText("Mem ingestion spike");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF17201D);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Paste or share a URL. The app runs packaged yt-dlp on-device and returns metadata only.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF4D5A55);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        root.addView(subtitle);

        urlInput = new EditText(this);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setHint("https://...");
        urlInput.setTextColor(0xFF17201D);
        urlInput.setHintTextColor(0xFF87908C);
        root.addView(urlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(12), 0, dp(12));

        extractButton = new Button(this);
        extractButton.setText("Extract metadata");
        extractButton.setOnClickListener(v -> runExtract(urlInput.getText().toString().trim()));
        controls.addView(extractButton);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        progressParams.setMargins(dp(12), 0, 0, 0);
        controls.addView(progress, progressParams);

        root.addView(controls);

        output = new TextView(this);
        output.setTextColor(0xFF17201D);
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setText("Ready.");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        return root;
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        String url = shared.toString().trim();
        urlInput.setText(url);
        if (!url.isEmpty()) runExtract(url);
    }

    private void runExtract(String url) {
        if (url.isEmpty()) {
            output.setText("Enter a URL first.");
            return;
        }
        setBusy(true);
        output.setText("Running packaged yt-dlp on-device...\n\n" + url);
        executor.submit(() -> {
            String result;
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();
                PyObject extractor = py.getModule("extractor");
                File filesDir = getFilesDir();
                String ffmpegPath = findPackagedExecutable("ffmpeg");
                PyObject raw = extractor.callAttr("extract", url, filesDir.getAbsolutePath(), ffmpegPath);
                result = prettyJson(raw.toString());
            } catch (Throwable t) {
                result = "Extraction failed:\n" + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            String finalResult = result;
            runOnUiThread(() -> {
                setBusy(false);
                output.setText(finalResult);
            });
        });
    }

    private void setBusy(boolean busy) {
        extractButton.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private String prettyJson(String json) {
        try {
            return new JSONObject(json).toString(2);
        } catch (Exception ignored) {
            return json;
        }
    }

    private String findPackagedExecutable(String name) {
        // Future FFmpeg hook: place per-ABI executable libs in jniLibs as libffmpeg.so
        // and libffprobe.so, then pass this path to yt-dlp's ffmpeg_location.
        File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File candidate = new File(nativeLibDir, "lib" + name + ".so");
        return candidate.exists() && candidate.canExecute() ? candidate.getAbsolutePath() : "";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

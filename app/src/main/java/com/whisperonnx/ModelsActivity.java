package com.whisperonnx;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.whisperonnx.models.ModelCatalog;
import com.whisperonnx.models.ModelDownloader;
import com.whisperonnx.models.SpeechModel;
import com.whisperonnx.utils.ThemeUtils;

import java.util.HashSet;
import java.util.Set;

public class ModelsActivity extends AppCompatActivity {
    private static final long REFRESH_MS = 1000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private final Set<String> verifying = new HashSet<>();
    private final Set<String> failedVerification = new HashSet<>();
    private final View[] rows = new View[ModelCatalog.ALL.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        LinearLayout list = findViewById(R.id.models_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < rows.length; i++) {
            rows[i] = inflater.inflate(R.layout.item_model, list, false);
            list.addView(rows[i]);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshLoop);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    private void refresh() {
        for (int i = 0; i < rows.length; i++) bind(rows[i], ModelCatalog.ALL[i]);
    }

    private void bind(View row, SpeechModel model) {
        TextView title = row.findViewById(R.id.model_title);
        TextView description = row.findViewById(R.id.model_description);
        TextView status = row.findViewById(R.id.model_status);
        ProgressBar progressBar = row.findViewById(R.id.model_progress);
        Button primary = row.findViewById(R.id.model_primary);
        Button delete = row.findViewById(R.id.model_delete);

        title.setText(model.title);
        description.setText(getString(model.descriptionRes, Formatter.formatShortFileSize(this, model.approximateSize)));
        progressBar.setVisibility(View.GONE);
        delete.setVisibility(View.GONE);
        primary.setVisibility(View.VISIBLE);
        primary.setEnabled(true);

        boolean installed = ModelCatalog.isInstalled(this, model);
        ModelDownloader.Progress progress = ModelDownloader.progress(this, model);

        if (verifying.contains(model.id) || (!installed && progress.state == ModelDownloader.State.COMPLETE)) {
            status.setText(R.string.model_verifying);
            primary.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            startVerification(model);
        } else if (installed) {
            bindInstalled(status, primary, delete, model);
        } else if (progress.state == ModelDownloader.State.RUNNING || progress.state == ModelDownloader.State.WAITING_FOR_WIFI) {
            if (progress.state == ModelDownloader.State.WAITING_FOR_WIFI) {
                status.setText(R.string.model_waiting_wifi);
            } else {
                status.setText(getString(R.string.model_downloading,
                        Formatter.formatShortFileSize(this, progress.downloaded),
                        Formatter.formatShortFileSize(this, progress.total)));
            }
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(progress.total <= 0);
            if (progress.total > 0) progressBar.setProgress((int) (progress.downloaded * 100 / progress.total));
            primary.setText(android.R.string.cancel);
            primary.setOnClickListener(v -> {
                ModelDownloader.cancel(this, model);
                refresh();
            });
        } else {
            boolean failed = progress.state == ModelDownloader.State.FAILED || failedVerification.contains(model.id);
            status.setText(failed ? R.string.model_failed : R.string.model_not_installed);
            primary.setText(failed ? R.string.model_retry : R.string.model_download);
            primary.setOnClickListener(v -> startDownload(model));
        }
    }

    private void bindInstalled(TextView status, Button primary, Button delete, SpeechModel model) {
        boolean selected = ModelCatalog.selected(this).id.equals(model.id);
        String[] timing = ModelCatalog.lastTiming(this, model);
        String timingText = timing == null ? "" : "\n" + getString(R.string.model_last_timing, timing[0], timing[1]);
        status.setText(getString(selected ? R.string.model_selected : R.string.model_installed) + timingText);
        if (selected) {
            primary.setVisibility(View.GONE);
        } else {
            primary.setText(R.string.model_use);
            primary.setOnClickListener(v -> {
                ModelCatalog.select(this, model);
                refresh();
            });
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.dictionary_delete_question, model.title))
                    .setPositiveButton(R.string.dictionary_delete, (dialog, which) -> {
                        ModelCatalog.delete(this, model);
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
    }

    private void startDownload(SpeechModel model) {
        failedVerification.remove(model.id);
        if (!ModelDownloader.hasSpaceFor(this, model)) {
            new AlertDialog.Builder(this).setMessage(R.string.model_no_space)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        ModelDownloader.start(this, model);
        refresh();
    }

    private void startVerification(SpeechModel model) {
        if (!verifying.add(model.id)) return;
        new Thread(() -> {
            boolean valid = ModelDownloader.verifyAndInstall(getApplicationContext(), model);
            handler.post(() -> {
                verifying.remove(model.id);
                if (!valid) failedVerification.add(model.id);
                refresh();
            });
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

package com.whisperonnx.models;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads through the system, so that a download survives leaving the app. */
public final class ModelDownloader {
    private static final String TAG = "ModelDownloader";

    public enum State { NONE, RUNNING, WAITING_FOR_WIFI, FAILED, COMPLETE }

    public static final class Progress {
        public final State state;
        public final long downloaded;
        public final long total;

        Progress(State state, long downloaded, long total) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
        }
    }

    private ModelDownloader() {}

    public static boolean hasSpaceFor(Context context, SpeechModel model) {
        StatFs stat = new StatFs(context.getExternalFilesDir(null).getPath());
        return stat.getAvailableBytes() > model.approximateSize * 2;
    }

    public static void start(Context context, SpeechModel model) {
        DownloadManager manager = manager(context);
        cancel(context, model);
        model.directory(context).mkdirs();
        StringBuilder ids = new StringBuilder();
        for (SpeechModel.ModelFile file : model.files) {
            model.file(context, file.name).delete();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(file.url))
                    .setTitle(model.title + ": " + file.name)
                    .setAllowedOverMetered(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, null, "models/" + model.id + "/" + file.name);
            if (ids.length() > 0) ids.append(',');
            ids.append(manager.enqueue(request));
        }
        prefs(context).edit().putString(key(model), ids.toString()).apply();
    }

    public static void cancel(Context context, SpeechModel model) {
        long[] ids = ids(context, model);
        if (ids.length > 0) manager(context).remove(ids);
        prefs(context).edit().remove(key(model)).apply();
    }

    public static Progress progress(Context context, SpeechModel model) {
        long[] ids = ids(context, model);
        if (ids.length == 0) return new Progress(State.NONE, 0, 0);

        long downloaded = 0;
        long total = 0;
        int successful = 0;
        int found = 0;
        boolean failed = false;
        boolean waitingForWifi = false;
        try (Cursor cursor = manager(context).query(new DownloadManager.Query().setFilterById(ids))) {
            while (cursor != null && cursor.moveToNext()) {
                found++;
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                downloaded += Math.max(0, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                total += Math.max(0, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                if (status == DownloadManager.STATUS_SUCCESSFUL) successful++;
                if (status == DownloadManager.STATUS_FAILED) failed = true;
                if (status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) waitingForWifi = true;
            }
        }
        if (total < model.approximateSize && model.files[0].size > 0) total = model.approximateSize;
        if (failed || found < ids.length) return new Progress(State.FAILED, downloaded, total);
        if (successful == ids.length) return new Progress(State.COMPLETE, downloaded, total);
        return new Progress(waitingForWifi ? State.WAITING_FOR_WIFI : State.RUNNING, downloaded, total);
    }

    /** Slow: call away from the UI thread. On failure nothing is left behind. */
    public static boolean verifyAndInstall(Context context, SpeechModel model) {
        boolean valid = true;
        try {
            for (SpeechModel.ModelFile file : model.files) {
                File downloaded = model.file(context, file.name);
                if (!downloaded.isFile()
                        || (file.size > 0 && downloaded.length() != file.size)
                        || (file.sha256 != null && !file.sha256.equals(sha256(downloaded)))) {
                    Log.e(TAG, "Invalid file: " + file.name);
                    valid = false;
                    break;
                }
            }
            if (valid) {
                if (model.engine == SpeechModel.Engine.ONNX_WHISPER) {
                    File zip = model.file(context, model.files[0].name);
                    extractZip(zip, context.getExternalFilesDir(null));
                    valid = ModelCatalog.isInstalled(context, model);
                } else if (!model.file(context, ModelCatalog.INSTALLED_MARKER).createNewFile()
                        && !model.file(context, ModelCatalog.INSTALLED_MARKER).isFile()) {
                    valid = false;
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "Could not install the model", e);
            valid = false;
        }
        if (valid) {
            prefs(context).edit().remove(key(model)).apply();
            if (model.engine == SpeechModel.Engine.ONNX_WHISPER) {
                File[] leftovers = model.directory(context).listFiles();
                if (leftovers != null) for (File leftover : leftovers) leftover.delete();
            }
        } else {
            cancel(context, model);
        }
        return valid;
    }

    private static void extractZip(File zip, File targetDirectory) throws IOException {
        String root = targetDirectory.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[1024 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                File target = new File(targetDirectory, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) throw new IOException("Invalid zip entry");
                if (entry.isDirectory()) continue;
                try (OutputStream output = Files.newOutputStream(target.toPath())) {
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
            }
        }
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    private static long[] ids(Context context, SpeechModel model) {
        String stored = prefs(context).getString(key(model), "");
        if (stored.isEmpty()) return new long[0];
        String[] parts = stored.split(",");
        long[] ids = new long[parts.length];
        for (int i = 0; i < parts.length; i++) ids[i] = Long.parseLong(parts[i]);
        return ids;
    }

    private static DownloadManager manager(Context context) {
        return (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static String key(SpeechModel model) {
        return "modelDownload_" + model.id;
    }
}

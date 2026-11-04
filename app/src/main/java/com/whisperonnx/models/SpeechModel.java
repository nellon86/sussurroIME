package com.whisperonnx.models;

import android.content.Context;

import java.io.File;

/** A speech model the app can download and use. */
public final class SpeechModel {
    public enum Engine { ONNX_WHISPER, SHERPA_PARAKEET, SHERPA_WHISPER }

    public static final class ModelFile {
        public final String name;
        public final String url;
        public final long size;      // 0 when unknown
        public final String sha256;  // null when unknown

        ModelFile(String name, String url, long size, String sha256) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public final String id;
    public final String title;
    public final int descriptionRes;
    public final Engine engine;
    public final long approximateSize;
    public final ModelFile[] files;

    SpeechModel(String id, String title, int descriptionRes, Engine engine, long approximateSize, ModelFile... files) {
        this.id = id;
        this.title = title;
        this.descriptionRes = descriptionRes;
        this.engine = engine;
        this.approximateSize = approximateSize;
        this.files = files;
    }

    public File directory(Context context) {
        return new File(context.getExternalFilesDir(null), "models/" + id);
    }

    public File file(Context context, String name) {
        return new File(directory(context), name);
    }
}

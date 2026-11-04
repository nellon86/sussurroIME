package com.whisperonnx.models;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.whisperonnx.R;

import java.io.File;
import java.util.Locale;

public final class ModelCatalog {
    private static final String PREF_SELECTED = "speechModel";
    private static final String HF = "https://huggingface.co/";
    private static final String PARAKEET = HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/";
    private static final String TURBO = HF + "csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/";
    static final String INSTALLED_MARKER = ".installed";
    static final String[] SMALL_FILES = {
            "Whisper_initializer.onnx", "Whisper_encoder.onnx", "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx", "Whisper_detokenizer.onnx"
    };

    public static final SpeechModel WHISPER_SMALL = new SpeechModel(
            "whisper_small", "Whisper small", R.string.model_small_description,
            SpeechModel.Engine.ONNX_WHISPER, 400_000_000L,
            new SpeechModel.ModelFile("whisper_small_int8.zip",
                    HF + "DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip", 0, null));

    public static final SpeechModel PARAKEET_V3 = new SpeechModel(
            "parakeet_v3", "Parakeet v3", R.string.model_parakeet_description,
            SpeechModel.Engine.SHERPA_PARAKEET, 670_478_572L,
            new SpeechModel.ModelFile("encoder.int8.onnx", PARAKEET + "encoder.int8.onnx", 652184281L,
                    "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            new SpeechModel.ModelFile("decoder.int8.onnx", PARAKEET + "decoder.int8.onnx", 11845275L,
                    "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            new SpeechModel.ModelFile("joiner.int8.onnx", PARAKEET + "joiner.int8.onnx", 6355277L,
                    "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            new SpeechModel.ModelFile("tokens.txt", PARAKEET + "tokens.txt", 93939L, null));

    public static final SpeechModel WHISPER_TURBO = new SpeechModel(
            "whisper_turbo", "Whisper Turbo", R.string.model_turbo_description,
            SpeechModel.Engine.SHERPA_WHISPER, 1_036_613_791L,
            new SpeechModel.ModelFile("turbo-encoder.int8.onnx", TURBO + "turbo-encoder.int8.onnx", 674716297L,
                    "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
            new SpeechModel.ModelFile("turbo-decoder.int8.onnx", TURBO + "turbo-decoder.int8.onnx", 361080764L,
                    "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
            new SpeechModel.ModelFile("turbo-tokens.txt", TURBO + "turbo-tokens.txt", 816730L, null));

    public static final SpeechModel[] ALL = {WHISPER_SMALL, PARAKEET_V3, WHISPER_TURBO};

    private ModelCatalog() {}

    public static SpeechModel selected(Context context) {
        String id = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_SELECTED, WHISPER_SMALL.id);
        for (SpeechModel model : ALL) {
            if (model.id.equals(id)) return model;
        }
        return WHISPER_SMALL;
    }

    public static void select(Context context, SpeechModel model) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_SELECTED, model.id).apply();
    }

    public static boolean isInstalled(Context context, SpeechModel model) {
        if (model.engine == SpeechModel.Engine.ONNX_WHISPER) {
            for (String name : SMALL_FILES) {
                if (!new File(context.getExternalFilesDir(null), name).isFile()) return false;
            }
            return true;
        }
        return model.file(context, INSTALLED_MARKER).isFile();
    }

    public static void delete(Context context, SpeechModel model) {
        if (model.engine == SpeechModel.Engine.ONNX_WHISPER) {
            for (String name : SMALL_FILES) {
                new File(context.getExternalFilesDir(null), name).delete();
            }
        }
        File[] files = model.directory(context).listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        model.directory(context).delete();
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(statsKey(model)).apply();
    }

    public static void recordTiming(Context context, SpeechModel model, long milliseconds, float audioSeconds) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(statsKey(model), String.format(Locale.ROOT, "%.1f|%.1f", milliseconds / 1000f, audioSeconds))
                .apply();
    }

    /** Null, or {seconds taken, seconds of audio} for the last dictation. */
    public static String[] lastTiming(Context context, SpeechModel model) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context).getString(statsKey(model), null);
        return stored == null ? null : stored.split("\\|");
    }

    private static String statsKey(SpeechModel model) {
        return "lastTiming_" + model.id;
    }
}

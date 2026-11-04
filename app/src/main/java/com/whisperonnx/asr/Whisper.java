package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.whisperonnx.ModelsActivity;
import com.whisperonnx.models.ModelCatalog;
import com.whisperonnx.models.SpeechModel;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_ERROR = "Engine error";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private volatile int[] mPromptTokens = new int[0];
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;
    private float audioSeconds;
    private final SpeechModel mModel;
    private final boolean mInstalled;
    private SherpaEngine sherpa = null;
    private Thread mWorker = null;

    public Whisper(Context context) {
        mContext = context;
        mModel = ModelCatalog.selected(context);
        mInstalled = ModelCatalog.isInstalled(context, mModel);

        if (!mInstalled) {
            Intent intent = new Intent(mContext, ModelsActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } else { // Start thread for RecordBuffer transcription
            mWorker = new Thread(this::processRecordBufferLoop);
            mWorker.start();
        }

    }

    /** False when another model was chosen after this instance was created. */
    public boolean isSelectedModel() {
        return mModel.id.equals(ModelCatalog.selected(mContext).id);
    }

    /** Whether the model takes the personal dictionary as prompt tokens. */
    public boolean usesPromptTokens() {
        return mModel.engine == SpeechModel.Engine.ONNX_WHISPER;
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel() {
        if (!mInstalled) return;
        if (mModel.engine != SpeechModel.Engine.ONNX_WHISPER) {
            sherpa = new SherpaEngine(mModel.directory(mContext), mModel.engine == SpeechModel.Engine.SHERPA_WHISPER);
            sherpa.load();
            return;
        }
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
            }
        });


        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                WhisperResult whisperResult = new WhisperResult(text,languageCode, mAction);

                sendResult(whisperResult);

                recordTiming();
                sendUpdate(MSG_PROCESSING_DONE);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "ERROR during recognition");
                reportError();
            }
        });
    }

    public void unloadModel() {
        if (mWorker != null) {
            mWorker.interrupt();
            mWorker = null;
        }
        if (sherpa != null) {
            sherpa.release();
            sherpa = null;
        }
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    public void setAction(Recognizer.Action action) {
        this.mAction = action;
    }

    public void setLanguage(String language){
        this.mLangCode = language;
    }

    public void setPromptTokens(int[] promptTokens) {
        this.mPromptTokens = promptTokens;
    }

    public void start() {
        if (!mInstalled) return;
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);
                float[] samples = RecordBuffer.getSamples();
                audioSeconds = samples.length / 16000f;
                if (sherpa != null) {
                    final Recognizer.Action action = mAction;
                    sherpa.transcribe(samples, mLangCode, action == Recognizer.ACTION_TRANSLATE, new SherpaEngine.Listener() {
                        @Override
                        public void onResult(String text, String language) {
                            sendResult(new WhisperResult(text, language, action));
                            recordTiming();
                            sendUpdate(MSG_PROCESSING_DONE);
                        }

                        @Override
                        public void onError() {
                            reportError();
                        }
                    });
                } else {
                    recognizer.setPromptTokens(mPromptTokens);
                    recognizer.recognize(samples,1, mLangCode, mAction );
                }
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void recordTiming() {
        long timeTaken = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
        ModelCatalog.recordTiming(mContext, mModel, timeTaken, audioSeconds);
    }

    private void reportError() {
        sendResult(new WhisperResult("", mLangCode, mAction));
        sendUpdate(MSG_ERROR);
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}

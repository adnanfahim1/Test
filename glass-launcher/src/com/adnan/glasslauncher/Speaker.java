package com.adnan.glasslauncher;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Reads messages aloud with the head unit's text-to-speech voice (if it has one). */
final class Speaker implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private boolean ready, failed;
    private String pending;
    private final Context ctx;

    Speaker(Context c) { ctx = c.getApplicationContext(); }

    /** False once it's known this head unit has no voice installed. */
    boolean available() { return !failed; }

    void say(String text) {
        if (text == null || failed) return;
        try {
            if (tts == null) {
                pending = text;
                tts = new TextToSpeech(ctx, this);
                return;
            }
            if (!ready) { pending = text; return; }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "glass-msg");
        } catch (Throwable t) {
            failed = true;
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            failed = true;
            return;
        }
        try {
            int r = tts.setLanguage(Locale.getDefault());
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        } catch (Throwable ignored) {}
        ready = true;
        if (pending != null) {
            String p = pending;
            pending = null;
            say(p);
        }
    }

    void stop() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
    }

    void shutdown() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        tts = null;
        ready = false;
    }
}

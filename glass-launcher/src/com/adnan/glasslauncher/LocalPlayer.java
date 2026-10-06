package com.adnan.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Plays songs from the head unit's own storage. It publishes a normal Android media
 * session, so the music bar, the Music screen, steering-wheel keys and the system media
 * controls all work with it like with any other music app.
 */
final class LocalPlayer implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    private static LocalPlayer instance;

    static LocalPlayer get(Context c) {
        if (instance == null) instance = new LocalPlayer(c.getApplicationContext());
        return instance;
    }

    /** The player if it was ever used in this process, without creating it. */
    static LocalPlayer peek() { return instance; }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final MediaSession session;
    private final MediaController controller;
    private MediaPlayer mp;
    private List<LocalMusic.Track> list = new ArrayList<LocalMusic.Track>();
    private int[] order = new int[0];
    private int pos = -1;
    private boolean prepared, playWhenReady, pausedByFocus, ducked, shuffle;
    private int errorsInRow;
    private long durationMs;
    private Bitmap art;
    private int artFor = -1;
    long lastPlayingAt;
    private Runnable onActive;

    private LocalPlayer(Context c) {
        ctx = c;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(c, "GlassLauncherPlayer");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stop(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long ms) { seekTo(ms); }
            @Override public void onFastForward() { seekTo(position() + 10000); }
            @Override public void onRewind() { seekTo(position() - 10000); }
            @Override public void onSkipToQueueItem(long id) { jumpTo((int) id); }

            @Override
            public boolean onMediaButtonEvent(Intent i) {
                KeyEvent e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (e != null && e.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK: toggle(); return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY: play(); return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE: pause(); return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT: next(); return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: prev(); return true;
                        default: break;
                    }
                }
                return super.onMediaButtonEvent(i);
            }
        }, main);
        controller = new MediaController(c, session.getSessionToken());
        publishState();
    }

    void setOnActive(Runnable r) { onActive = r; }

    MediaController controller() { return controller; }
    MediaSession.Token token() { return session.getSessionToken(); }

    boolean hasTrack() { return pos >= 0 && pos < order.length; }
    boolean isPlaying() { return mp != null && prepared && playWhenReady; }
    boolean shuffle() { return shuffle; }
    int queuePosition() { return pos; }

    LocalMusic.Track current() { return hasTrack() ? list.get(order[pos]) : null; }

    long position() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Throwable t) { return 0; }
    }

    long duration() { return durationMs; }

    Bitmap art() { return art; }

    // ---- Commands ---------------------------------------------------------------------------
    /** Plays {@code tracks} starting at {@code start}; shuffled when {@code shuffle} is true. */
    void playList(List<LocalMusic.Track> tracks, int start, boolean shuffle) {
        if (tracks == null || tracks.isEmpty()) return;
        list = new ArrayList<LocalMusic.Track>(tracks);
        this.shuffle = shuffle;
        order = new int[list.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        start = Math.max(0, Math.min(list.size() - 1, start));
        if (shuffle) {
            List<Integer> rest = new ArrayList<Integer>();
            for (int i = 0; i < order.length; i++) if (i != start) rest.add(i);
            Collections.shuffle(rest, new Random());
            order[0] = start;
            for (int i = 0; i < rest.size(); i++) order[i + 1] = rest.get(i);
            pos = 0;
        } else {
            pos = start;
        }
        errorsInRow = 0;
        load(true);
    }

    void play() {
        if (!hasTrack()) return;
        if (mp == null) { load(true); return; }
        if (!requestFocus()) return;
        playWhenReady = true;
        pausedByFocus = false;
        if (prepared) {
            try { mp.start(); } catch (Throwable t) { onError(mp, 0, 0); return; }
        }
        changed();
    }

    void pause() {
        playWhenReady = false;
        if (mp != null && prepared) {
            try { mp.pause(); } catch (Throwable ignored) {}
        }
        changed();
    }

    void toggle() { if (isPlaying()) pause(); else play(); }

    void next() {
        if (!hasTrack()) return;
        pos = (pos + 1) % order.length;
        load(true);
    }

    void prev() {
        if (!hasTrack()) return;
        if (position() > 3000) { seekTo(0); return; }
        pos = (pos - 1 + order.length) % order.length;
        load(true);
    }

    void seekTo(long ms) {
        if (mp == null || !prepared) return;
        long d = durationMs > 0 ? durationMs : Long.MAX_VALUE;
        try { mp.seekTo((int) Math.max(0, Math.min(d - 500, ms))); } catch (Throwable ignored) {}
        changed();
    }

    /** Queue ids are positions in the play order. */
    void jumpTo(int queuePos) {
        if (queuePos < 0 || queuePos >= order.length) return;
        pos = queuePos;
        load(true);
    }

    void stop() {
        playWhenReady = false;
        release();
        pos = -1;
        list = new ArrayList<LocalMusic.Track>();
        order = new int[0];
        art = null;
        artFor = -1;
        abandonFocus();
        session.setActive(false);
        changed();
    }

    // ---- MediaPlayer ------------------------------------------------------------------------
    private void load(boolean play) {
        release();
        final LocalMusic.Track t = current();
        if (t == null) return;
        playWhenReady = play && requestFocus();
        prepared = false;
        durationMs = t.duration;
        mp = new MediaPlayer();
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try { mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK); } catch (Throwable ignored) {}
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            if (t.uri.startsWith("content:")) mp.setDataSource(ctx, Uri.parse(t.uri));
            else mp.setDataSource(t.uri);
            mp.prepareAsync();
        } catch (Throwable e) {
            onError(mp, 0, 0);
            return;
        }
        session.setActive(true);
        loadArt(pos, t);
        changed();
    }

    private void release() {
        prepared = false;
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    @Override
    public void onPrepared(MediaPlayer p) {
        if (p != mp) return;
        prepared = true;
        errorsInRow = 0;
        try {
            int d = p.getDuration();
            if (d > 0) durationMs = d;
        } catch (Throwable ignored) {}
        if (playWhenReady) {
            try { p.start(); } catch (Throwable t) { onError(p, 0, 0); return; }
        }
        changed();
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        if (p != mp) return;
        if (pos + 1 < order.length) {
            pos++;
            load(true);
        } else {
            // End of the list: back to the first song, paused.
            pos = 0;
            load(false);
        }
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        if (p != null && p != mp) return true;
        errorsInRow++;
        release();
        if (errorsInRow >= Math.min(5, Math.max(1, order.length))) {
            // Several files in a row can't be played (removed SD card, unsupported format).
            playWhenReady = false;
            errorsInRow = 0;
            changed();
            return true;
        }
        final boolean keepPlaying = playWhenReady;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!hasTrack()) return;
                pos = (pos + 1) % order.length;
                load(keepPlaying);
            }
        });
        return true;
    }

    // ---- Audio focus (phone calls, navigation prompts, other music apps) ---------------------
    private boolean requestFocus() {
        try {
            return audio == null || audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void abandonFocus() {
        try { if (audio != null) audio.abandonAudioFocus(this); } catch (Throwable ignored) {}
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocus = false;
                pause();
                abandonFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    pause();
                    pausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null) try { mp.setVolume(0.3f, 0.3f); ducked = true; } catch (Throwable ignored) {}
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (ducked && mp != null) try { mp.setVolume(1f, 1f); } catch (Throwable ignored) {}
                ducked = false;
                if (pausedByFocus) play();
                break;
            default:
                break;
        }
    }

    // ---- Album art ---------------------------------------------------------------------------
    private void loadArt(final int forPos, final LocalMusic.Track t) {
        if (artFor == forPos && art != null) return;
        art = null;
        artFor = forPos;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (t.uri.startsWith("content:")) r.setDataSource(ctx, Uri.parse(t.uri));
                    else r.setDataSource(t.uri);
                    byte[] pic = r.getEmbeddedPicture();
                    if (pic != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                        int s = 1;
                        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 400) s *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = s;
                        b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    }
                    if (t.artist == null) t.artist = trim(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                    String title = trim(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                    if (title != null && t.path != null && t.uri.equals(t.path)) t.title = title;
                } catch (Throwable ignored) {
                } finally {
                    try { r.release(); } catch (Throwable ignored) {}
                }
                final Bitmap result = b;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (artFor != forPos) return;
                        art = result;
                        changed();
                    }
                });
            }
        }, "album-art").start();
    }

    private static String trim(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.length() == 0 ? null : s;
    }

    // ---- Session state -----------------------------------------------------------------------
    private void changed() {
        if (isPlaying()) lastPlayingAt = SystemClock.elapsedRealtime();
        publishState();
        PlaybackService.update(ctx, this);
        if (onActive != null) {
            try { onActive.run(); } catch (Throwable ignored) {}
        }
    }

    private void publishState() {
        try {
            int state = !hasTrack() ? PlaybackState.STATE_NONE
                    : isPlaying() ? PlaybackState.STATE_PLAYING
                    : playWhenReady && !prepared ? PlaybackState.STATE_BUFFERING
                    : PlaybackState.STATE_PAUSED;
            PlaybackState.Builder pb = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                    .setState(state, position(), isPlaying() ? 1f : 0f, SystemClock.elapsedRealtime());
            if (hasTrack()) pb.setActiveQueueItemId(pos);
            session.setPlaybackState(pb.build());

            LocalMusic.Track t = current();
            if (t == null) {
                session.setMetadata(null);
                session.setQueue(null);
                return;
            }
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist != null ? t.artist : LocalMusic.SOURCE_NAMES[t.source])
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
            if (t.album != null) mb.putString(MediaMetadata.METADATA_KEY_ALBUM, t.album);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(mb.build());
            session.setQueue(queueWindow());
        } catch (Throwable ignored) {
            // Session updates must never stop playback.
        }
    }

    /** Up to 150 upcoming songs (a full library is too large to send through Android). */
    List<MediaSession.QueueItem> queueWindow() {
        List<MediaSession.QueueItem> q = new ArrayList<MediaSession.QueueItem>();
        int from = Math.max(0, pos - 20), to = Math.min(order.length, pos + 130);
        for (int i = from; i < to; i++) {
            LocalMusic.Track t = list.get(order[i]);
            MediaDescription d = new MediaDescription.Builder()
                    .setMediaId(t.uri).setTitle(t.title).setSubtitle(t.subtitle()).build();
            q.add(new MediaSession.QueueItem(d, i));
        }
        return q;
    }
}

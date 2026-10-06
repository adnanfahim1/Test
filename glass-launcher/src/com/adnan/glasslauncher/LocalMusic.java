package com.adnan.glasslauncher;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Songs stored on the head unit: internal memory, SD card and USB drives. Android's media
 * index is read first. "Scan storage" also walks the folders for songs the head unit
 * hasn't indexed (common on some firmwares) and asks Android to index them for next time.
 */
final class LocalMusic {
    static final int SRC_INTERNAL = 0, SRC_SD = 1, SRC_USB = 2;
    static final String[] SOURCE_NAMES = {"Internal", "SD card", "USB"};

    static final class Track {
        String uri;      // content:// or plain file path
        String path;     // file path when known
        String title;
        String artist;
        String album;
        long duration;   // ms, 0 when unknown
        int source;

        String subtitle() {
            StringBuilder b = new StringBuilder();
            b.append(artist != null ? artist : "Unknown artist");
            b.append(" · ").append(SOURCE_NAMES[source]);
            if (duration > 0) b.append(" · ").append(Ui.mmss(duration));
            return b.toString();
        }
    }

    private static final String[] EXT = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".oga", ".opus", ".wav",
            ".wma", ".amr", ".mka", ".alac", ".aiff", ".aif", ".ape"};

    private static List<Track> cache;
    private static boolean loading;
    private static final List<Runnable> waiting = new ArrayList<Runnable>();

    private LocalMusic() {}

    static String permission() {
        return NewApi.SDK >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean hasPermission(Context c) { return NewApi.granted(c, permission()); }

    /** The last loaded library, or null when it hasn't been loaded yet. */
    static List<Track> cached() { return cache; }

    static boolean isLoading() { return loading; }

    /** Storage was mounted, removed or re-indexed: load again next time it's needed. */
    static void invalidate() { if (!loading) cache = null; }

    /** Loads the library on a background thread; {@code done} runs on the main thread. */
    static void load(Context ctx, final boolean walkFolders, Runnable done) {
        final Context c = ctx.getApplicationContext();
        if (done != null) waiting.add(done);
        if (loading) return;
        loading = true;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Track> out;
                try {
                    out = query(c);
                    if (walkFolders || out.isEmpty()) out = merge(c, out, walk(c));
                } catch (Throwable t) {
                    out = new ArrayList<Track>();
                }
                final List<Track> result = out;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cache = result;
                        loading = false;
                        List<Runnable> rs = new ArrayList<Runnable>(waiting);
                        waiting.clear();
                        for (Runnable r : rs) {
                            try { r.run(); } catch (Throwable ignored) {}
                        }
                    }
                });
            }
        }, "music-library").start();
    }

    // ---- Android's media index -------------------------------------------------------------
    static List<Track> query(Context c) {
        List<Track> out = new ArrayList<Track>();
        if (!hasPermission(c)) return out;
        List<String> volumes = NewApi.audioVolumes(c);
        if (volumes == null) {
            read(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, out);
        } else {
            for (String v : volumes) read(c, MediaStore.Audio.Media.getContentUri(v), v, out);
        }
        sort(out);
        return out;
    }

    private static void read(Context c, Uri base, String volume, List<Track> out) {
        ContentResolver cr = c.getContentResolver();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA};
        Cursor cur = null;
        try {
            cur = cr.query(base, cols, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (cur == null) return;
            while (cur.moveToNext()) {
                Track t = new Track();
                long id = cur.getLong(0);
                t.uri = ContentUris.withAppendedId(base, id).toString();
                t.title = clean(cur.getString(1));
                t.artist = clean(cur.getString(2));
                t.album = clean(cur.getString(3));
                t.duration = cur.isNull(4) ? 0 : cur.getLong(4);
                t.path = cur.getString(5);
                if (t.title == null) t.title = t.path != null ? baseName(t.path) : "Track " + id;
                t.source = sourceOf(c, t.path, volume);
                out.add(t);
            }
        } catch (Throwable ignored) {
            // A volume can disappear (SD card pulled out) while it is being read.
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
        }
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 0 || "<unknown>".equalsIgnoreCase(s)) return null;
        return s;
    }

    private static final java.util.Map<String, Integer> rootSource = new java.util.HashMap<String, Integer>();

    static int sourceOf(Context c, String path, String volume) {
        String p = path != null ? path.toLowerCase(Locale.US) : "";
        if (p.contains("usb") || p.contains("udisk") || p.contains("otg")) return SRC_USB;
        if ("external_primary".equals(volume)) return SRC_INTERNAL;
        if (p.length() == 0) return volume == null ? SRC_INTERNAL : SRC_SD;
        if (primary(p)) return SRC_INTERNAL;
        // Removable: ask Android whether the volume is an SD card or a USB drive (7.0+).
        String[] seg = path.split("/");
        String root = seg.length > 2 ? "/" + seg[1] + "/" + seg[2] : path;
        synchronized (rootSource) {
            Integer known = rootSource.get(root);
            if (known != null) return known;
            String label = NewApi.volumeDescription(c, new File(root));
            int src = label != null && label.toLowerCase(Locale.US).contains("usb") ? SRC_USB : SRC_SD;
            rootSource.put(root, src);
            return src;
        }
    }

    private static boolean primary(String lowerPath) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath().toLowerCase(Locale.US);
            return lowerPath.startsWith(root) || lowerPath.startsWith("/sdcard/") || lowerPath.startsWith("/storage/emulated/")
                    || lowerPath.startsWith("/mnt/sdcard/") || lowerPath.startsWith("/data/");
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- Folder walk (songs the media index missed) ------------------------------------------
    static List<Track> walk(Context c) {
        List<Track> out = new ArrayList<Track>();
        if (!hasPermission(c)) return out;
        Set<String> roots = new LinkedHashSet<String>();
        try {
            roots.add(Environment.getExternalStorageDirectory().getAbsolutePath());
        } catch (Throwable ignored) {}
        try {
            for (File f : c.getExternalFilesDirs(null)) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/");
                if (i > 0) roots.add(p.substring(0, i));
            }
        } catch (Throwable ignored) {}
        for (String parent : new String[]{"/storage", "/mnt", "/mnt/media_rw", "/mnt/usb", "/udisk"}) {
            File[] kids = new File(parent).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("secure") || n.equals("asec") || n.equals("obb")
                        || n.equals("runtime") || n.equals("user") || n.equals("vendor") || n.startsWith(".")) continue;
                if (k.isDirectory() && k.canRead()) roots.add(k.getAbsolutePath());
            }
        }
        Set<String> seenDirs = new HashSet<String>();
        List<String> newPaths = new ArrayList<String>();
        int visited = 0;
        for (String root : roots) {
            ArrayDeque<File> stack = new ArrayDeque<File>();
            ArrayDeque<Integer> depth = new ArrayDeque<Integer>();
            stack.push(new File(root));
            depth.push(0);
            while (!stack.isEmpty() && visited < 40000 && out.size() < 8000) {
                File dir = stack.pop();
                int d = depth.pop();
                String key;
                try { key = dir.getCanonicalPath(); } catch (Throwable t) { key = dir.getAbsolutePath(); }
                if (!seenDirs.add(key)) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    visited++;
                    String n = f.getName();
                    if (n.startsWith(".")) continue;
                    if (f.isDirectory()) {
                        if (d < 10 && !(d == 0 && n.equals("Android"))) {
                            stack.push(f);
                            depth.push(d + 1);
                        }
                    } else if (isAudio(n) && f.length() > 64 * 1024) {
                        Track t = new Track();
                        t.path = f.getAbsolutePath();
                        t.uri = t.path;
                        t.title = baseName(n);
                        File p = f.getParentFile();
                        t.album = p != null ? p.getName() : null;
                        t.source = sourceOf(c, t.path, null);
                        out.add(t);
                        newPaths.add(t.path);
                    }
                }
            }
        }
        // Ask Android to index what was found, so it has full details next time.
        if (!newPaths.isEmpty()) {
            try {
                MediaScannerConnection.scanFile(c, newPaths.toArray(new String[newPaths.size()]), null, null);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String e : EXT) if (n.endsWith(e)) return true;
        return false;
    }

    private static String baseName(String path) {
        String n = path;
        int s = n.lastIndexOf('/');
        if (s >= 0) n = n.substring(s + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace('_', ' ');
    }

    /** Index results first; folder results only for files the index doesn't have. */
    static List<Track> merge(Context c, List<Track> indexed, List<Track> walked) {
        Set<String> known = new HashSet<String>();
        for (Track t : indexed) if (t.path != null) known.add(canon(t.path));
        List<Track> out = new ArrayList<Track>(indexed);
        for (Track t : walked) if (known.add(canon(t.path))) out.add(t);
        sort(out);
        return out;
    }

    private static String canon(String p) {
        try { return new File(p).getCanonicalPath(); } catch (Throwable t) { return p; }
    }

    private static void sort(List<Track> list) {
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(list, new Comparator<Track>() {
            @Override
            public int compare(Track x, Track y) { return col.compare(x.title, y.title); }
        });
    }

    static int count(List<Track> list, int source) {
        int n = 0;
        if (list != null) for (Track t : list) if (t.source == source) n++;
        return n;
    }
}

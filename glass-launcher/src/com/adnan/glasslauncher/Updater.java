package com.adnan.glasslauncher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Receives a new version of this launcher from the Glass Link phone app (in chunks over the
 * phone link), checks it and installs it after the driver confirms on the head unit.
 *
 * Accepted only when the file is complete (SHA-256 matches), is this same app, is signed
 * with the same key (so settings are kept) and isn't older than the installed version.
 */
final class Updater {
    static final long MAX_SIZE = 100L * 1024 * 1024;

    static final class Ready {
        File file;
        String versionName;
        int versionCode;
    }

    private final Context ctx;
    private File file;
    private OutputStream out;
    private MessageDigest digest;
    private long expected, received;
    private int nextSeq;
    private String expectedSha;

    Updater(Context c) { ctx = c.getApplicationContext(); }

    boolean receiving() { return out != null; }

    int percent() { return expected > 0 ? (int) (received * 100 / expected) : 0; }

    /** Starts a transfer; returns an error message or null. */
    String begin(long size, String sha256) {
        abort();
        if (size <= 0 || size > MAX_SIZE) return "The update file is too large";
        try {
            File dir = new File(ctx.getCacheDir(), "update");
            dir.mkdirs();
            file = new File(dir, "GlassLauncher-update.apk");
            out = new FileOutputStream(file);
            digest = MessageDigest.getInstance("SHA-256");
            expected = size;
            received = 0;
            nextSeq = 0;
            expectedSha = sha256 != null ? sha256.toLowerCase(java.util.Locale.US) : null;
            return null;
        } catch (Exception e) {
            abort();
            return "Not enough space on the head unit for the update";
        }
    }

    /** Writes one chunk; returns an error message or null. */
    String chunk(int seq, String base64) {
        if (out == null) return "No update in progress";
        if (seq != nextSeq) return "Part of the update was lost; try again";
        try {
            byte[] b = Base64.decode(base64, Base64.DEFAULT);
            if (received + b.length > expected) return "The update is larger than announced";
            out.write(b);
            digest.update(b);
            received += b.length;
            nextSeq++;
            return null;
        } catch (Exception e) {
            abort();
            return "Couldn't save the update (storage full?)";
        }
    }

    /** Finishes the transfer and checks the file. Throws with a readable message when it's not acceptable. */
    Ready finish() throws Exception {
        if (out == null) throw new Exception("No update in progress");
        try { out.close(); } finally { out = null; }
        if (received != expected) throw new Exception("The update arrived incomplete; try again");
        String sha = hex(digest.digest());
        if (expectedSha != null && !expectedSha.equals(sha)) throw new Exception("The update arrived damaged; try again");
        return check(ctx, file);
    }

    void abort() {
        if (out != null) try { out.close(); } catch (Exception ignored) {}
        out = null;
        if (file != null) file.delete();
    }

    @SuppressWarnings("deprecation")
    static Ready check(Context ctx, File f) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (update == null) throw new Exception("That file isn't a valid app");
        if (!ctx.getPackageName().equals(update.packageName)) {
            throw new Exception("That file is a different app (" + update.packageName + "), not Glass Launcher");
        }
        PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
        if (update.versionCode < mine.versionCode) {
            throw new Exception("That's an older version (" + update.versionName + ") than the one installed (" + mine.versionName + ")");
        }
        if (update.signatures != null && update.signatures.length > 0 && !sameSigners(mine.signatures, update.signatures)) {
            throw new Exception("That version is signed with a different key, so it can't update this one");
        }
        Ready r = new Ready();
        r.file = f;
        r.versionName = update.versionName;
        r.versionCode = update.versionCode;
        return r;
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        String[] x = new String[a.length], y = new String[b.length];
        for (int i = 0; i < a.length; i++) { x[i] = a[i].toCharsString(); y[i] = b[i].toCharsString(); }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /**
     * Hands the file to Android's installer. Android then asks the driver to confirm (and,
     * the first time, to allow installs from Glass Launcher). The launcher restarts updated.
     */
    static void install(Context ctx, File f) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        p.setSize(f.length());
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream o = s.openWrite("base.apk", 0, f.length());
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            } finally {
                in.close();
                o.close();
            }
            Intent done = new Intent(ctx, Result.class);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, done, NewApi.mutable(PendingIntent.FLAG_UPDATE_CURRENT));
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /** Android's answer: show its confirmation screen, or report a failure. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Throwable ignored) {}
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(c, "Update not installed" + (msg != null ? ": " + msg : ""),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}

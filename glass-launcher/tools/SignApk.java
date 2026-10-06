import com.android.apksig.ApkSigner;
import com.android.apksig.ApkSignerEngine;
import com.android.apksig.ApkVerifier;
import com.android.apksig.DefaultApkSignerEngine;
import com.android.apksig.util.DataSource;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Adds an APK Signature Scheme v2 signature (Android 7.0+) with apksig, keeping the v1 JAR
 * signature that jarsigner already added (needed by Android 5.0-6.0).
 * apksig 2.3.0 can't create v1 itself on current JDKs, hence the two-step signing.
 *
 *   sign:   SignApk in.apk out.apk keystore.p12 password alias
 *   verify: SignApk --verify app.apk      (checks Android 5.0 through current)
 */
public class SignApk {
    public static void main(String[] a) throws Exception {
        if ("--verify".equals(a[0])) {
            verify(new File(a[1]), 21, 22, "Android 5.0-5.1 (v1)");
            verify(new File(a[1]), 23, 23, "Android 6.0 (v1)");
            verify(new File(a[1]), 24, 30, "Android 7.0-11 (v2)");
            verify(new File(a[1]), 31, 35, "Android 12-15 (v2)");
            return;
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[2])) { ks.load(in, a[3].toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey(a[4], a[3].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[4]);
        DefaultApkSignerEngine.SignerConfig cfg = new DefaultApkSignerEngine.SignerConfig.Builder(
                "CERT", key, Collections.singletonList(cert)).build();
        final DefaultApkSignerEngine v2 = new DefaultApkSignerEngine.Builder(Collections.singletonList(cfg), 21)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).build();
        new ApkSigner.Builder(new KeepV1(v2))
                .setInputApk(new File(a[0]))
                .setOutputApk(new File(a[1]))
                .build()
                .sign();
    }

    /**
     * Delegates to apksig's engine (v2 only) but keeps the jarsigner v1 files in META-INF
     * instead of dropping them, so the result carries both signatures.
     */
    static final class KeepV1 implements ApkSignerEngine {
        private final ApkSignerEngine d;

        KeepV1(ApkSignerEngine d) { this.d = d; }

        private static boolean v1File(String n) {
            String u = n.toUpperCase(java.util.Locale.ROOT);
            return u.startsWith("META-INF/") && (u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF")
                    || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"));
        }

        @Override public void inputApkSigningBlock(DataSource b) throws java.io.IOException, com.android.apksig.apk.ApkFormatException { d.inputApkSigningBlock(b); }
        @Override public InputJarEntryInstructions inputJarEntry(String n) {
            if (v1File(n)) return new InputJarEntryInstructions(InputJarEntryInstructions.OutputPolicy.OUTPUT);
            return d.inputJarEntry(n);
        }
        @Override public InspectJarEntryRequest outputJarEntry(String n) { return v1File(n) ? null : d.outputJarEntry(n); }
        @Override public InputJarEntryInstructions.OutputPolicy inputJarEntryRemoved(String n) { return d.inputJarEntryRemoved(n); }
        @Override public void outputJarEntryRemoved(String n) { d.outputJarEntryRemoved(n); }
        @Override public OutputJarSignatureRequest outputJarEntries() throws com.android.apksig.apk.ApkFormatException,
                java.security.NoSuchAlgorithmException, java.security.InvalidKeyException, java.security.SignatureException { return d.outputJarEntries(); }
        @Override public OutputApkSigningBlockRequest outputZipSections(DataSource z, DataSource cd, DataSource eocd)
                throws java.io.IOException, com.android.apksig.apk.ApkFormatException, java.security.NoSuchAlgorithmException,
                java.security.InvalidKeyException, java.security.SignatureException { return d.outputZipSections(z, cd, eocd); }
        @Override public void outputDone() { d.outputDone(); }
        @Override public void close() { d.close(); }
    }

    private static void verify(File apk, int min, int max, String label) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(min)
                .setMaxCheckedPlatformVersion(max).build().verify();
        System.out.println("   signature " + label + ": " + (r.isVerified() ? "OK" : "FAILED")
                + (r.isVerifiedUsingV1Scheme() ? " v1" : "") + (r.isVerifiedUsingV2Scheme() ? " v2" : ""));
        if (!r.isVerified()) {
            for (Object e : r.getErrors()) System.out.println("     " + e);
            System.exit(1);
        }
    }
}

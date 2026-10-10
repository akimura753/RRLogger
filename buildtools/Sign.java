import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class Sign {
    public static void main(String[] a) throws Exception {
        String ksPath = a[0], pass = a[1], alias = a[2], in = a[3], out = a[4];
        int minSdk = Integer.parseInt(a[5]);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(ksPath)) { ks.load(f, pass.toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "rrlogger", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(in)).setOutputApk(new File(out))
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(out)).setMinCheckedPlatformVersion(minSdk).build().verify();
        System.out.println("verified=" + r.isVerified()
                + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme()
                + " v3=" + r.isVerifiedUsingV3Scheme());
        for (ApkVerifier.IssueWithParams e : r.getErrors()) System.out.println("ERROR: " + e);
        for (ApkVerifier.IssueWithParams w : r.getWarnings()) System.out.println("WARN: " + w);
        for (X509Certificate c : r.getSignerCertificates()) System.out.println("signer: " + c.getSubjectX500Principal());
        if (!r.isVerified()) System.exit(1);
    }
}

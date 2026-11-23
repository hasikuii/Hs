// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Owns the RSA 2048 adb keypair used for both the pairing (PeerInfo) and the
 * TLS connection (client certificate). Keys are persisted as PKCS#8 PEM at
 * {@code files/adb/adbkey} and the self-signed certificate as DER at
 * {@code files/adb/adbkey.crt.der}, mirroring the classic {@code ~/.android}
 * layout. The keypair is generated lazily on first use and reused afterwards.
 */
public final class AdbKeyManager {
    public static final String DEFAULT_DEVICE_NAME = "termux-adb";

    private final Context mContext;
    private final File mDir;
    private final String mDeviceName;
    private KeyPair mKeyPair;

    public AdbKeyManager(Context context, String deviceName) {
        mContext = context.getApplicationContext();
        mDir = new File(mContext.getFilesDir(), "adb");
        mDeviceName = deviceName != null ? deviceName : DEFAULT_DEVICE_NAME;
    }

    public AdbKeyManager(Context context) {
        this(context, DEFAULT_DEVICE_NAME);
    }

    public synchronized KeyPair getKeyPair() throws Exception {
        if (mKeyPair != null) {
            return mKeyPair;
        }
        File privFile = new File(mDir, "adbkey");
        File certFile = new File(mDir, "adbkey.crt.der");
        if (privFile.exists() && certFile.exists() && privFile.length() > 0 && certFile.length() > 0) {
            mKeyPair = loadKeyPair(privFile, certFile);
        } else {
            mKeyPair = generateKeyPair(privFile, certFile);
        }
        return mKeyPair;
    }

    private KeyPair generateKeyPair(File privFile, File certFile) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        java.security.KeyPair kp = kpg.generateKeyPair();
        RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();

        byte[] certDer = AdbCertificates.buildSelfSignedCert(pub, priv, mDeviceName);

        if (!mDir.exists() && !mDir.mkdirs()) {
            throw new IOException("Cannot create adb key dir: " + mDir);
        }
        writePem(privFile, "PRIVATE KEY", priv.getEncoded());
        writeRaw(certFile, certDer);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(certDer));
        return new KeyPair(priv, cert);
    }

    private KeyPair loadKeyPair(File privFile, File certFile) throws Exception {
        byte[] pkcs8 = readPem(privFile, "PRIVATE KEY");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        byte[] certDer = readRaw(certFile);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(certDer));
        return new KeyPair(priv, cert);
    }

    /** SHA-256 fingerprint of the SubjectPublicKeyInfo (matches {@code adb} convention). */
    public String getFingerprint() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) getKeyPair().getPublicKey();
        byte[] spki = pub.getEncoded();
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(spki);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sha.length; i++) {
            if (i > 0 && i % 2 == 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", sha[i]));
        }
        return sb.toString();
    }

    public String getDeviceName() {
        return mDeviceName;
    }

    public File getKeyDir() {
        return mDir;
    }

    private static void writePem(File f, String type, byte[] der) throws IOException {
        String b64 = Base64.encodeToString(der, Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readPem(File f, String type) throws IOException {
        String content = new String(readAll(f), StandardCharsets.UTF_8);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int s = content.indexOf(begin);
        int e = content.indexOf(end);
        if (s < 0 || e < 0) {
            throw new IOException("PEM block " + type + " not found in " + f);
        }
        String b64 = content.substring(s + begin.length(), e).replaceAll("\\s+", "");
        return Base64.decode(b64, Base64.DEFAULT);
    }

    private static void writeRaw(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static byte[] readRaw(File f) throws IOException {
        return readAll(f);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        }
    }
}

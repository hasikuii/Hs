// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Builds a self-signed X.509 v3 certificate over an RSA 2048 keypair so the
 * ADB TLS handshake (adbd always uses {@code SetCertVerifyCallback -> 1},
 * i.e. accepts any client cert that carries an authorized public key) can
 * present a parseable certificate without dragging in BouncyCastle.
 */
final class AdbCertificates {
    private static final byte[] OID_SHA256_RSA = DerWriter.oid("1.2.840.113549.1.1.11");
    private static final byte[] OID_RSA_ENCRYPTION = DerWriter.oid("1.2.840.113549.1.1.1");
    private static final byte[] OID_CN = DerWriter.oid("2.5.4.3");

    private AdbCertificates() {
    }

    private static byte[] algIdSha256Rsa() {
        return DerWriter.seq(OID_SHA256_RSA, DerWriter.nullDer());
    }

    private static byte[] nameCn(String cn) {
        byte[] attr = DerWriter.seq(OID_CN, DerWriter.utf8(cn));
        return DerWriter.seq(DerWriter.set(attr));
    }

    static byte[] buildSelfSignedCert(RSAPublicKey pub, RSAPrivateKey priv, String cn) throws Exception {
        byte[] algId = algIdSha256Rsa();
        byte[] name = nameCn(cn);
        byte[] spki = pub.getEncoded();
        byte[] validity = DerWriter.seq(
                DerWriter.utcTime("260101000000Z"),
                DerWriter.utcTime("491231235959Z"));

        byte[] serialBytes = new byte[16];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] = (byte) (serialBytes[0] & 0x7F);
        BigInteger serial = new BigInteger(1, serialBytes);

        byte[] tbs = DerWriter.seq(
                DerWriter.explicit(0, DerWriter.integer(BigInteger.valueOf(2))),
                DerWriter.integer(serial),
                algId,
                name,
                validity,
                name,
                spki);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(priv);
        sig.update(tbs);
        byte[] signature = sig.sign();

        return DerWriter.seq(tbs, algId, DerWriter.bitString(signature));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        byte[] h = sha256(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

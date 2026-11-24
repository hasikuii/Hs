// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869) hand-rolled on top of {@code javax.crypto.Mac}.
 * Used to derive the AES-128-GCM key from the SPAKE2 shared secret in
 * {@link PairingAuthCtx}, replacing the BouncyCastle HKDFBytesGenerator.
 */
final class HkdfSha256 {
    private static final int HASH_LEN = 32;

    private HkdfSha256() {
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, IKM). {@code salt} null/empty means 32 zero bytes. */
    static byte[] extract(byte[] salt, byte[] ikm) {
        if (salt == null || salt.length == 0) {
            salt = new byte[HASH_LEN];
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return mac.doFinal(ikm);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
        }
    }

    /** HKDF-Expand: writes {@code out.length} bytes of HKDF(PRK, info). */
    static void expand(byte[] prk, byte[] info, byte[] out) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            int blocks = (out.length + HASH_LEN - 1) / HASH_LEN;
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; i <= blocks; i++) {
                mac.reset();
                mac.update(t);
                if (info != null) {
                    mac.update(info);
                }
                mac.update((byte) i);
                t = mac.doFinal();
                int take = Math.min(HASH_LEN, out.length - pos);
                System.arraycopy(t, 0, out, pos, take);
                pos += take;
            }
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
        }
    }
}

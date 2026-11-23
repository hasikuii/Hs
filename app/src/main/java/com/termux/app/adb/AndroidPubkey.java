// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Adapted from AOSP libcrypto_utils/android_pubkey.cpp via the Muntashirakon
// libadb port. Encodes an RSA public key into Android's custom binary format
// (modulus_size_words, n0inv, modulus LE, rr LE, exponent), base64-encodes it,
// appends " <name>\0" — the format adbd stores in /data/misc/adb/adb_keys.
// Also signs the ADB AUTH token with RSA/SHA-1 PKCS#1 v1.5 (NoPadding path).
package com.termux.app.adb;

import android.util.Base64;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

final class AndroidPubkey {
    private static final int MODULUS_SIZE = 2048 / 8;
    private static final int ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE;
    private static final int MODULUS_SIZE_WORDS = MODULUS_SIZE / 4;

    private AndroidPubkey() {
    }

    static byte[] adbAuthSign(PrivateKey privateKey, byte[] token) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, privateKey);
        return c.doFinal(token);
    }

    static byte[] encodeWithName(RSAPublicKey publicKey, String name) throws InvalidKeyException {
        byte[] encoded = encode(publicKey);
        byte[] b64 = Base64.encode(encoded, Base64.NO_WRAP);
        byte[] suffix = (" " + name + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[b64.length + suffix.length];
        System.arraycopy(b64, 0, out, 0, b64.length);
        System.arraycopy(suffix, 0, out, b64.length, suffix.length);
        return out;
    }

    static byte[] encode(RSAPublicKey publicKey) throws InvalidKeyException {
        BigInteger n = publicKey.getModulus();
        if (n.toByteArray().length < MODULUS_SIZE) {
            throw new InvalidKeyException("RSA modulus too small");
        }
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ZERO.setBit(MODULUS_SIZE * 8).modPow(BigInteger.valueOf(2), n);

        ByteBuffer key = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        key.putInt(MODULUS_SIZE_WORDS);
        key.putInt(n0inv.intValue());
        key.put(toLittleEndianPadded(MODULUS_SIZE, n));
        key.put(toLittleEndianPadded(MODULUS_SIZE, rr));
        key.putInt(publicKey.getPublicExponent().intValue());
        return key.array();
    }

    private static byte[] toLittleEndianPadded(int len, BigInteger in) {
        byte[] be = in.toByteArray();
        byte[] le = swap(be);
        byte[] out = new byte[len];
        int n = Math.min(len, le.length);
        System.arraycopy(le, 0, out, 0, n);
        return out;
    }

    private static byte[] swap(byte[] bytes) {
        int len = bytes.length;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = bytes[len - i - 1];
        }
        return out;
    }
}

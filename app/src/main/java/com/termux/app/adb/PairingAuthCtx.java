// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Ported from the Muntashirakon libadb PairingAuthCtx (which used BouncyCastle)
// to plain JCE (AES/GCM/NoPadding) + hand-rolled HKDF-SHA256. The SPAKE2 core
// in com.termux.app.adb.crypto.spake2 is unchanged. Mirrors AOSP
// adb/pairing_auth/pairing_auth.cpp:
//   - SPAKE2 (Ristretto255/Edwards25519) with my_name="adb pair client\0",
//     their_name="adb pair server\0"
//   - AES-128-GCM key = HKDF-SHA256(spake2_key_material, salt=null,
//                     info="adb pairing_auth aes-128-gcm key")
//   - 12-byte little-endian counter IV, incremented per encrypt / per decrypt
package com.termux.app.adb;

import com.termux.app.adb.crypto.spake2.Spake2Context;
import com.termux.app.adb.crypto.spake2.Spake2Role;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Destroyable;

class PairingAuthCtx implements Destroyable {
    private static final byte[] CLIENT_NAME = StringCompat.getBytes("adb pair client\u0000", "UTF-8");
    private static final byte[] SERVER_NAME = StringCompat.getBytes("adb pair server\u0000", "UTF-8");
    private static final byte[] INFO = StringCompat.getBytes("adb pairing_auth aes-128-gcm key", "UTF-8");
    private static final int HKDF_KEY_LENGTH = 128 / 8;
    public static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] mMsg;
    private final Spake2Context mSpake2Ctx;
    private final byte[] mSecretKey = new byte[HKDF_KEY_LENGTH];
    private long mDecIv = 0;
    private long mEncIv = 0;
    private boolean mIsDestroyed = false;

    static PairingAuthCtx createAlice(byte[] password) {
        Spake2Context ctx = new Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME);
        try {
            return new PairingAuthCtx(ctx, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    static PairingAuthCtx createBob(byte[] password) {
        Spake2Context ctx = new Spake2Context(Spake2Role.Bob, SERVER_NAME, CLIENT_NAME);
        try {
            return new PairingAuthCtx(ctx, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    private PairingAuthCtx(Spake2Context ctx, byte[] password)
            throws IllegalArgumentException, IllegalStateException {
        mSpake2Ctx = ctx;
        mMsg = mSpake2Ctx.generateMessage(password);
    }

    public byte[] getMsg() {
        return mMsg;
    }

    public boolean initCipher(byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) {
            return false;
        }
        byte[] keyMaterial = mSpake2Ctx.processMessage(theirMsg);
        if (keyMaterial == null) {
            return false;
        }
        byte[] prk = HkdfSha256.extract(null, keyMaterial);
        HkdfSha256.expand(prk, INFO, mSecretKey);
        Arrays.fill(prk, (byte) 0);
        return true;
    }

    public byte[] encrypt(byte[] in) {
        return encryptDecrypt(true, in, nextIv(mEncIv++));
    }

    public byte[] decrypt(byte[] in) {
        return encryptDecrypt(false, in, nextIv(mDecIv++));
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public void destroy() {
        mIsDestroyed = true;
        Arrays.fill(mSecretKey, (byte) 0);
        mSpake2Ctx.destroy();
    }

    private static byte[] nextIv(long counter) {
        ByteBuffer b = ByteBuffer.allocate(GCM_IV_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(counter);
        return b.array();
    }

    private byte[] encryptDecrypt(boolean forEncryption, byte[] in, byte[] iv) {
        if (mIsDestroyed) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(mSecretKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(in);
        } catch (Exception e) {
            return null;
        }
    }
}

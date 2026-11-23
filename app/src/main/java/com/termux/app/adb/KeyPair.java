// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;

/** Holds the RSA private key + self-signed certificate used for ADB TLS / auth. */
final class KeyPair {
    private final PrivateKey mPrivateKey;
    private final Certificate mCertificate;

    KeyPair(PrivateKey privateKey, Certificate certificate) {
        mPrivateKey = privateKey;
        mCertificate = certificate;
    }

    PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    PublicKey getPublicKey() {
        return mCertificate.getPublicKey();
    }

    Certificate getCertificate() {
        return mCertificate;
    }
}

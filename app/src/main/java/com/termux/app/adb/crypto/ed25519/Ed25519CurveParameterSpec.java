/*
 * Copyright (C) 2021 Muntashir Al-Islam
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.termux.app.adb.crypto.ed25519;

import java.io.Serializable;

/**
 * Parameter specification for Ed25519 curve.
 */
public class Ed25519CurveParameterSpec implements java.security.spec.AlgorithmParameterSpec, Serializable {
    public static final String ED_25519 = "Ed25519";

    private static final long serialVersionUID = 8274987108472012L;

    private final Curve curve;
    private final String hashAlgo;
    private final GroupElement B;

    public Ed25519CurveParameterSpec(Curve curve, String hashAlgo, GroupElement B) {
        try {
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance(hashAlgo);
            // EdDSA hash function must produce 2b-bit output
            if (curve.getField().getb() / 4 != hash.getDigestLength())
                throw new IllegalArgumentException("Hash output is not 2b-bit");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm");
        }

        this.curve = curve;
        this.hashAlgo = hashAlgo;
        this.B = B;
    }

    public String getName() {
        return ED_25519;
    }

    public Curve getCurve() {
        return curve;
    }

    public String getHashAlgorithm() {
        return hashAlgo;
    }

    /**
     * @return the base (generator)
     */
    public GroupElement getB() {
        return B;
    }

    @Override
    public int hashCode() {
        return hashAlgo.hashCode() ^ curve.hashCode() ^ B.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Ed25519CurveParameterSpec))
            return false;
        Ed25519CurveParameterSpec s = (Ed25519CurveParameterSpec) o;
        return hashAlgo.equals(s.getHashAlgorithm()) &&
                curve.equals(s.getCurve()) &&
                B.equals(s.getB());
    }
}

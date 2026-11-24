// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/**
 * Minimal ASN.1 DER writer used to hand-build a self-signed X.509 v3 certificate
 * for the ADB TLS handshake. No BouncyCastle dependency.
 */
final class DerWriter {
    private DerWriter() {
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag & 0xFF);
        writeLen(o, content.length);
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static void writeLen(ByteArrayOutputStream o, int len) {
        if (len < 0x80) {
            o.write(len);
        } else if (len <= 0xFF) {
            o.write(0x81);
            o.write(len);
        } else if (len <= 0xFFFF) {
            o.write(0x82);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        } else {
            throw new IllegalArgumentException("DER length too large: " + len);
        }
    }

    static byte[] seq(byte[]... parts) {
        return concat(0x30, parts);
    }

    static byte[] set(byte[]... parts) {
        return concat(0x31, parts);
    }

    private static byte[] concat(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            body.write(p, 0, p.length);
        }
        return tlv(tag, body.toByteArray());
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] integer(long v) {
        return integer(BigInteger.valueOf(v));
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid OID: " + dotted);
        }
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            long val = Long.parseLong(parts[i]);
            if (val == 0) {
                body.write(0);
                continue;
            }
            byte[] tmp = new byte[9];
            int pos = tmp.length - 1;
            tmp[pos] = (byte) (val & 0x7F);
            val >>>= 7;
            pos--;
            while (val > 0) {
                tmp[pos] = (byte) (0x80 | (val & 0x7F));
                val >>>= 7;
                pos--;
            }
            body.write(tmp, pos + 1, tmp.length - pos - 1);
        }
        return tlv(0x06, body.toByteArray());
    }

    static byte[] bitString(byte[] data) {
        byte[] body = new byte[data.length + 1];
        System.arraycopy(data, 0, body, 1, data.length);
        return tlv(0x03, body);
    }

    static byte[] utcTime(String s) {
        return tlv(0x17, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] nullDer() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] explicit(int tagNum, byte[] content) {
        return tlv(0xA0 | (tagNum & 0x1F), content);
    }
}

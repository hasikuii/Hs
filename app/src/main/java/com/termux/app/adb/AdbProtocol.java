// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Adapted from Cameron Gutman's AdbLib (BSD-3-Clause) via the Muntashirakon
// libadb port. Minimal ADB wire-protocol framing for Termux: 24-byte
// little-endian header (command, arg0, arg1, data_length, data_check, magic)
// with a byte-sum checksum and magic = ~command.
package com.termux.app.adb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AdbProtocol {
    static final int ADB_HEADER_LENGTH = 24;
    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    static final byte[] SYSTEM_IDENTITY_STRING_HOST = "host::\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final int MAX_PAYLOAD = 4 * 1024;
    static final int A_VERSION = 0x01000000;
    static final int A_STLS_VERSION = 0x01000000;

    static final int ADB_AUTH_TOKEN = 1;
    static final int ADB_AUTH_SIGNATURE = 2;
    static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private AdbProtocol() {
    }

    private static int checksum(byte[] data, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i++) {
            sum += data[i] & 0xFF;
        }
        return sum;
    }

    static byte[] generateMessage(int command, int arg0, int arg1, byte[] data, int off, int len) {
        ByteBuffer m = ByteBuffer.allocate(ADB_HEADER_LENGTH + len).order(ByteOrder.LITTLE_ENDIAN);
        m.putInt(command);
        m.putInt(arg0);
        m.putInt(arg1);
        m.putInt(len);
        m.putInt(len == 0 ? 0 : checksum(data, off, len));
        m.putInt(~command);
        if (len > 0) {
            m.put(data, off, len);
        }
        return m.array();
    }

    static byte[] generateMessage(int command, int arg0, int arg1, byte[] data) {
        return generateMessage(command, arg0, arg1, data, 0, data == null ? 0 : data.length);
    }

    static byte[] generateConnect() {
        return generateMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, SYSTEM_IDENTITY_STRING_HOST);
    }

    static byte[] generateAuth(int type, byte[] data) {
        return generateMessage(A_AUTH, type, 0, data);
    }

    static byte[] generateOpen(int localId, String destination) {
        byte[] d = destination.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withNul = new byte[d.length + 1];
        System.arraycopy(d, 0, withNul, 0, d.length);
        return generateMessage(A_OPEN, localId, 0, withNul);
    }

    static byte[] generateWrite(int localId, int remoteId, byte[] data, int off, int len) {
        return generateMessage(A_WRTE, localId, remoteId, data, off, len);
    }

    static byte[] generateClose(int localId, int remoteId) {
        return generateMessage(A_CLSE, localId, remoteId, null);
    }

    static byte[] generateReady(int localId, int remoteId) {
        return generateMessage(A_OKAY, localId, remoteId, null);
    }

    static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final int dataLength;
        final int dataCheck;
        final int magic;
        byte[] payload;

        private Message(ByteBuffer h) {
            command = h.getInt();
            arg0 = h.getInt();
            arg1 = h.getInt();
            dataLength = h.getInt();
            dataCheck = h.getInt();
            magic = h.getInt();
        }

        static Message parse(InputStream in) throws IOException {
            byte[] header = new byte[ADB_HEADER_LENGTH];
            int read = 0;
            while (read < ADB_HEADER_LENGTH) {
                int n = in.read(header, read, ADB_HEADER_LENGTH - read);
                if (n < 0) {
                    throw new IOException("Stream closed");
                }
                read += n;
            }
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            Message msg = new Message(h);
            if (msg.command != (~msg.magic)) {
                throw new IOException("Invalid magic");
            }
            if (msg.dataLength < 0 || msg.dataLength > MAX_PAYLOAD) {
                throw new IOException("Invalid data length " + msg.dataLength);
            }
            if (msg.dataLength > 0) {
                msg.payload = new byte[msg.dataLength];
                read = 0;
                while (read < msg.dataLength) {
                    int n = in.read(msg.payload, read, msg.dataLength - read);
                    if (n < 0) {
                        throw new IOException("Stream closed");
                    }
                    read += n;
                }
            }
            return msg;
        }
    }
}

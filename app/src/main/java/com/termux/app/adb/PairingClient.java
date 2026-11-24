// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Implements the AOSP wireless-debugging pairing handshake (Android 11+) as a
 * TLS 1.3 client.
 *
 * <p>Mirrors {@code system/adb/pairing_client/pairing_client.cpp} and
 * {@code system/adb/pairing_auth/pairing_auth.cpp}. The handshake is:
 *
 * <ol>
 *   <li>TCP connect to {@code host:pairPort} (the port shown next to the
 *       6-digit code in Android's Wireless debugging → "Pair device with
 *       pairing code" screen).</li>
 *   <li>TLS 1.3 handshake. The pairing server accepts any client certificate
 *       during pairing — the real authentication happens below via SPAKE2 +
 *       AES-128-GCM, not via the TLS cert.</li>
 *   <li>Exchange {@code PairingPacket} frames (4-byte BE version, 4-byte BE
 *       type, 4-byte BE payload-size, payload):
 *     <ul>
 *       <li>Send type=1, payload = our SPAKE2 message (32 bytes, plaintext
 *           under TLS).</li>
 *       <li>Receive type=1, payload = peer's SPAKE2 message (32 bytes).</li>
 *       <li>Call {@code PairingAuthCtx.initCipher(peerMsg)} to derive the
 *           AES-128-GCM key from the SPAKE2 shared secret via HKDF-SHA256.</li>
 *       <li>Build our peer-info payload: 4-byte BE version=1, 4-byte BE
 *           type=1 (pubkey), 4-byte BE length, then the RSA public key in
 *           Android {@code adb_keys} binary+base64 format.</li>
 *       <li>AES-GCM encrypt the peer-info payload with enc-IV counter=0.</li>
 *       <li>Send type=2, payload = encrypted peer-info.</li>
 *       <li>Receive type=2, payload = server's encrypted peer-info.</li>
 *       <li>AES-GCM decrypt with dec-IV counter=0.</li>
 *       <li>Validate the server's peer-info: version=1, type=2
 *           (primary_peer_info).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>On success, the pairing server has appended our RSA public key to
 * {@code /data/misc/adb/adb_keys}, and subsequent
 * {@link AdbConnection#create} calls to the connection port will succeed.
 *
 * <p>This class is single-use: create an instance, call {@link #pair(int)},
 * discard. The SPAKE2 context inside {@link PairingAuthCtx} is destroyed
 * after each attempt.
 */
final class PairingClient {

    /** Pairing protocol version (matches AOSP {@code kPairingVersion}). */
    private static final int PAIRING_VERSION = 1;

    /** Packet type: SPAKE2 message, 32-byte plaintext payload. */
    private static final int PAIRING_PACKET_SPAKE2_MSG = 1;

    /** Packet type: peer info, AES-GCM encrypted payload. */
    private static final int PAIRING_PACKET_PEER_INFO = 2;

    /** Peer-info field type: the RSA public key in adb_keys format. */
    private static final int PEER_INFO_TYPE_PUBKEY = 1;

    /** Peer-info field type: primary peer info from the server. */
    private static final int PEER_INFO_TYPE_PRIMARY = 2;

    private static final int SPAKE2_MSG_SIZE = 32;
    private static final int HEADER_SIZE = 12;
    private static final int MAX_PAYLOAD = 16 * 1024;

    private final String mHost;
    private final KeyPair mKeyPair;
    private final String mDeviceName;

    PairingClient(String host, KeyPair keyPair, String deviceName) {
        this.mHost = host;
        this.mKeyPair = keyPair;
        this.mDeviceName = deviceName != null ? deviceName : AdbKeyManager.DEFAULT_DEVICE_NAME;
    }

    /**
     * Run the pairing handshake against {@code host:port}.
     *
     * @param port     the pairing port (the one displayed next to the 6-digit
     *                 code in Android's Wireless debugging screen — NOT the
     *                 connection port).
     * @param password the 6-digit pairing code as displayed by Android. May
     *                 contain non-digit characters; they are stripped.
     * @param timeoutSec overall timeout for the whole handshake.
     * @throws IOException on any network, TLS, SPAKE2, or peer-info failure.
     *                     The message is Vietnamese and user-readable.
     */
    void pair(int port, String password, int timeoutSec) throws IOException {
        // Strip everything that isn't an ASCII digit from the password — Android
        // shows the code as 6 plain digits, but IME auto-fill / clipboard paste
        // may inject separators or whitespace.
        String digits = password == null ? "" : password.replaceAll("\\D", "");
        if (digits.length() != 6) {
            throw new IOException("Mã ghép nối phải có đúng 6 chữ số (nhận được " + digits.length() + ")");
        }
        byte[] passwordBytes = digits.getBytes(StandardCharsets.US_ASCII);

        PairingAuthCtx authCtx = PairingAuthCtx.createAlice(passwordBytes);
        if (authCtx == null) {
            throw new IOException("Không thể khởi tạo SPAKE2 (ghi log keypair hỏng?)");
        }

        Socket plain = null;
        SSLSocket tls = null;
        try {
            plain = new Socket();
            plain.connect(new InetSocketAddress(mHost, port), (int) TimeUnit.SECONDS.toMillis(timeoutSec));
            plain.setSoTimeout((int) TimeUnit.SECONDS.toMillis(timeoutSec));

            SSLContext sslContext = SslUtils.getSslContext(mKeyPair);
            tls = (SSLSocket) sslContext.getSocketFactory().createSocket(plain, mHost, port, true);
            // Pairing server uses TLS 1.3 only (A_STLS upgrades adbd's plain TCP
            // to TLS 1.3 before the pairing handshake begins).
            tls.setUseClientMode(true);
            tls.startHandshake();

            DataOutputStream out = new DataOutputStream(tls.getOutputStream());
            DataInputStream in = new DataInputStream(tls.getInputStream());

            // 1. Send our SPAKE2 message (plaintext under TLS).
            byte[] ourSpake2Msg = authCtx.getMsg();
            if (ourSpake2Msg == null || ourSpake2Msg.length != SPAKE2_MSG_SIZE) {
                throw new IOException("SPAKE2 không sinh được message");
            }
            writePacket(out, PAIRING_PACKET_SPAKE2_MSG, ourSpake2Msg);

            // 2. Receive peer's SPAKE2 message.
            byte[] peerSpake2Msg = readPacket(in, PAIRING_PACKET_SPAKE2_MSG);
            if (peerSpake2Msg.length != SPAKE2_MSG_SIZE) {
                throw new IOException("SPAKE2 message nhận được có độ dài sai: " + peerSpake2Msg.length);
            }

            // 3. Derive AES key from SPAKE2 shared secret.
            if (!authCtx.initCipher(peerSpake2Msg)) {
                throw new IOException("SPAKE2 key agreement thất bại — mã ghép nối sai hoặc peer point không hợp lệ");
            }

            // 4. Build + encrypt our peer info (RSA pubkey in adb_keys format).
            byte[] peerInfo = buildPeerInfo(mKeyPair, mDeviceName);
            byte[] encrypted = authCtx.encrypt(peerInfo);
            if (encrypted == null) {
                throw new IOException("AES-GCM mã hóa peer info thất bại");
            }
            writePacket(out, PAIRING_PACKET_PEER_INFO, encrypted);

            // 5. Receive + decrypt server's peer info.
            byte[] encServerInfo = readPacket(in, PAIRING_PACKET_PEER_INFO);
            byte[] serverInfo = authCtx.decrypt(encServerInfo);
            if (serverInfo == null) {
                throw new IOException("AES-GCM giải mã peer info của server thất bại — mã ghép nối có thể sai");
            }
            validateServerPeerInfo(serverInfo);

            // If we got here, the server accepted our pubkey and wrote it to
            // /data/misc/adb/adb_keys. The handshake is complete.
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // SSLException, NoSuchAlgorithmException, KeyManagementException, etc.
            throw new IOException("Lỗi ghép nối: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            quietClose(tls);
            quietClose(plain);
            authCtx.destroy();
        }
    }

    /** Serialize a pairing packet: 4-byte BE version, 4-byte BE type, 4-byte BE size, payload. */
    private static void writePacket(DataOutputStream out, int type, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IOException("Payload quá lớn: " + payload.length + " > " + MAX_PAYLOAD);
        }
        out.writeInt(PAIRING_VERSION);
        out.writeInt(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /** Read + validate a pairing packet. Returns the payload. */
    private static byte[] readPacket(DataInputStream in, int expectedType) throws IOException {
        int version = in.readInt();
        int type = in.readInt();
        int size = in.readInt();
        if (version != PAIRING_VERSION) {
            throw new IOException("Phiên bản pairing packet không khớp: " + version + " ≠ " + PAIRING_VERSION);
        }
        if (type != expectedType) {
            throw new IOException("Loại packet không mong đợi: " + type + " ≠ " + expectedType);
        }
        if (size < 0 || size > MAX_PAYLOAD) {
            throw new IOException("Kích thước packet không hợp lệ: " + size);
        }
        byte[] payload = new byte[size];
        in.readFully(payload);
        return payload;
    }

    /**
     * Build the unencrypted peer-info payload that we send to the server.
     *
     * <p>Layout (all BE): 4-byte version=1, 4-byte type=1 (pubkey), 4-byte
     * length, then the RSA public key in Android {@code adb_keys} format
     * (binary 256-byte modulus + exponent, base64 + " name\0").
     */
    private static byte[] buildPeerInfo(KeyPair keyPair, String deviceName) throws IOException {
        byte[] pubKeyBytes;
        try {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublicKey();
            pubKeyBytes = AndroidPubkey.encodeWithName(pub, deviceName);
        } catch (Exception e) {
            throw new IOException("Không encode được RSA pubkey: " + e.getMessage(), e);
        }
        ByteBuffer buf = ByteBuffer.allocate(12 + pubKeyBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(1);                                  // version
        buf.putInt(PEER_INFO_TYPE_PUBKEY);              // type
        buf.putInt(pubKeyBytes.length);                 // length
        buf.put(pubKeyBytes);
        return buf.array();
    }

    /**
     * Validate the server's decrypted peer-info payload. We don't strictly
     * require any specific contents — the fact that AES-GCM authenticated
     * successfully already proves the server derived the same SPAKE2 key,
     * which proves it knew the 6-digit code. We just sanity-check the
     * version and type fields so a malformed response doesn't silently pass.
     */
    private static void validateServerPeerInfo(byte[] payload) throws IOException {
        if (payload.length < 12) {
            throw new IOException("Peer info của server quá ngắn: " + payload.length + " byte");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = buf.getInt();
        int type = buf.getInt();
        int length = buf.getInt();
        if (version != 1) {
            throw new IOException("Phiên bản peer info server không hợp lệ: " + version);
        }
        if (type != PEER_INFO_TYPE_PRIMARY && type != PEER_INFO_TYPE_PUBKEY) {
            throw new IOException("Loại peer info server không hợp lệ: " + type);
        }
        if (length < 0 || length > payload.length - 12) {
            throw new IOException("Độ dài peer info server không khớp: header=" + length + ", thực=" + (payload.length - 12));
        }
        // Payload contents (device name etc.) are informational — ignore.
    }

    private static void quietClose(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }
}

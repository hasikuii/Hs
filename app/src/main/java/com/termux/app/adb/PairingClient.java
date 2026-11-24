// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
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
 * <p>Mirrors Shizuku's {@code moe.shizuku.manager.adb.AdbPairingClient} and
 * AOSP {@code system/adb/pairing_server/pairing_server.cpp}. The handshake is:
 *
 * <ol>
 *   <li>TCP connect to {@code host:pairPort} (the port shown next to the
 *       6-digit code in Android's Wireless debugging → "Pair device with
 *       pairing code" screen).</li>
 *   <li>TLS 1.3 handshake with the app's RSA keypair. <b>After</b> the
 *       handshake, export 64 bytes of keying material from the TLS session
 *       via {@code Conscrypt.exportKeyingMaterial(sslSocket, "adb-label\0",
 *       null, 64)}. The SPAKE2 password is <b>NOT</b> just the 6-digit code —
 *       it is {@code pairCode (6 bytes) || keyingMaterial (64 bytes) = 70 bytes}.
 *       This binds the SPAKE2 exchange to the TLS session, preventing MITM
 *       even if the 6-digit code is weak.</li>
 *   <li>Exchange {@code PairingPacketHeader} frames (6 bytes: 1-byte version,
 *       1-byte type, 4-byte BE payload-size) + payload:
 *     <ul>
 *       <li>Send type=0 (SPAKE2_MSG), payload = our SPAKE2 message (32 bytes,
 *           plaintext under TLS).</li>
 *       <li>Receive type=0, payload = peer's SPAKE2 message (32 bytes).</li>
 *       <li>Call {@code PairingAuthCtx.initCipher(peerMsg)} to derive the
 *           AES-128-GCM key from the SPAKE2 shared secret via HKDF-SHA256.</li>
 *       <li>Build our peer-info: exactly {@code kMaxPeerInfoSize = 8192} bytes
 *           — 1-byte type=0 (ADB_RSA_PUB_KEY) + 8191 bytes data (the RSA
 *           public key in Android {@code adb_keys} binary+base64 format,
 *           zero-padded to 8191 bytes).</li>
 *       <li>AES-GCM encrypt the 8192-byte peer-info with enc-IV counter=0.</li>
 *       <li>Send type=1 (PEER_INFO), payload = encrypted peer-info.</li>
 *       <li>Receive type=1, payload = server's encrypted peer-info.</li>
 *       <li>AES-GCM decrypt with dec-IV counter=0.</li>
 *       <li>Validate the decrypted payload is exactly 8192 bytes — proves the
 *           server derived the same SPAKE2 key, which proves it knew the
 *           6-digit code.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>On success, the pairing server has appended our RSA public key to
 * {@code /data/misc/adb/adb_keys}, and subsequent
 * {@link AdbConnection#create} calls to the connection port will succeed.
 *
 * <p>This class is single-use: create an instance, call {@link #pair}, discard.
 */
final class PairingClient {

    // ---- Pairing protocol constants (match Shizuku / AOSP) -----------------
    private static final byte kCurrentKeyHeaderVersion = 1;
    private static final int  kMaxPeerInfoSize  = 8192;
    private static final int  kMaxPayloadSize   = kMaxPeerInfoSize * 2;
    private static final int  kPairingPacketHeaderSize = 6;

    /** TLS exported-keying-material label (with embedded NUL — AOSP convention). */
    private static final String kExportedKeyLabel = "adb-label\u0000";
    private static final int    kExportedKeySize  = 64;

    // PairingPacketHeader.type values (NOTE: 0-based, not 1-based)
    private static final byte TYPE_SPAKE2_MSG = 0;
    private static final byte TYPE_PEER_INFO  = 1;

    // PeerInfo.type values
    @SuppressWarnings("unused")
    private static final byte PEER_INFO_TYPE_ADB_RSA_PUB_KEY = 0;

    private static final int SPAKE2_MSG_SIZE = 32;

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
     * @param pairCode the 6-digit pairing code as displayed by Android.
     * @param timeoutSec overall timeout for the whole handshake.
     * @throws IOException on any network, TLS, SPAKE2, or peer-info failure.
     *                     The message is Vietnamese and user-readable.
     */
    void pair(int port, String pairCode, int timeoutSec) throws IOException {
        // Strip everything that isn't an ASCII digit from the pair code.
        String digits = pairCode == null ? "" : pairCode.replaceAll("\\D", "");
        if (digits.length() != 6) {
            throw new IOException("Mã ghép nối phải có đúng 6 chữ số (nhận được " + digits.length() + ")");
        }
        byte[] pairCodeBytes = digits.getBytes(StandardCharsets.US_ASCII);

        Socket plain = null;
        SSLSocket tls = null;
        PairingAuthCtx authCtx = null;
        try {
            // 1. TCP connect
            plain = new Socket();
            plain.connect(new InetSocketAddress(mHost, port), (int) TimeUnit.SECONDS.toMillis(timeoutSec));
            plain.setSoTimeout((int) TimeUnit.SECONDS.toMillis(timeoutSec));
            plain.setTcpNoDelay(true);

            // 2. TLS 1.3 handshake with our RSA keypair
            SSLContext sslContext = SslUtils.getSslContext(mKeyPair);
            tls = (SSLSocket) sslContext.getSocketFactory().createSocket(plain, mHost, port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();

            // 3. Export 64 bytes of keying material from the TLS session.
            //    The SPAKE2 password is pairCode (6) || keyingMaterial (64) = 70 bytes.
            //    Without this, the server's SPAKE2 derivation will mismatch and the
            //    AES-GCM decrypt of the server's peer-info will fail.
            byte[] keyingMaterial = exportKeyingMaterial(tls, kExportedKeyLabel, null, kExportedKeySize);
            if (keyingMaterial == null || keyingMaterial.length != kExportedKeySize) {
                throw new IOException("Không export được keying material từ TLS session "
                    + "(cần Conscrypt — Android 10+ mặc định có sẵn). "
                    + "Nhận được: " + (keyingMaterial == null ? "null" : keyingMaterial.length + " bytes"));
            }
            byte[] passwordBytes = new byte[pairCodeBytes.length + keyingMaterial.length];
            System.arraycopy(pairCodeBytes, 0, passwordBytes, 0, pairCodeBytes.length);
            System.arraycopy(keyingMaterial, 0, passwordBytes, pairCodeBytes.length, keyingMaterial.length);

            // 4. Create SPAKE2 context (Alice role = client)
            authCtx = PairingAuthCtx.createAlice(passwordBytes);
            if (authCtx == null) {
                throw new IOException("Không thể khởi tạo SPAKE2 context");
            }

            DataOutputStream out = new DataOutputStream(tls.getOutputStream());
            DataInputStream in = new DataInputStream(tls.getInputStream());

            // 5. Exchange SPAKE2 messages (type=0)
            byte[] ourSpake2Msg = authCtx.getMsg();
            if (ourSpake2Msg == null || ourSpake2Msg.length != SPAKE2_MSG_SIZE) {
                throw new IOException("SPAKE2 không sinh được message");
            }
            writePacket(out, TYPE_SPAKE2_MSG, ourSpake2Msg);

            byte[] peerSpake2Msg = readPacket(in, TYPE_SPAKE2_MSG);
            if (peerSpake2Msg.length != SPAKE2_MSG_SIZE) {
                throw new IOException("SPAKE2 message nhận được có độ dài sai: " + peerSpake2Msg.length);
            }

            // 6. Derive AES-128-GCM key from SPAKE2 shared secret via HKDF-SHA256
            if (!authCtx.initCipher(peerSpake2Msg)) {
                throw new IOException("SPAKE2 key agreement thất bại — mã ghép nối sai hoặc peer point không hợp lệ");
            }

            // 7. Build + encrypt our peer-info (exactly 8192 bytes)
            byte[] peerInfo = buildPeerInfo(mKeyPair, mDeviceName);
            byte[] encrypted = authCtx.encrypt(peerInfo);
            if (encrypted == null) {
                throw new IOException("AES-GCM mã hóa peer info thất bại");
            }
            writePacket(out, TYPE_PEER_INFO, encrypted);

            // 8. Receive + decrypt server's peer-info
            byte[] encServerInfo = readPacket(in, TYPE_PEER_INFO);
            byte[] serverInfo = authCtx.decrypt(encServerInfo);
            if (serverInfo == null) {
                throw new IOException("AES-GCM giải mã peer info của server thất bại — mã ghép nối có thể sai");
            }
            if (serverInfo.length != kMaxPeerInfoSize) {
                throw new IOException("Peer info của server có độ dài sai: " + serverInfo.length
                    + " ≠ " + kMaxPeerInfoSize);
            }
            // The fact that AES-GCM authenticated + the size matches already proves
            // the server knew the 6-digit code. We don't need to parse the contents.

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Lỗi ghép nối: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            quietClose(tls);
            quietClose(plain);
            if (authCtx != null) authCtx.destroy();
        }
    }

    // ---- Packet framing (6-byte header: 1 version + 1 type + 4 BE payload) -

    private static void writePacket(DataOutputStream out, byte type, byte[] payload) throws IOException {
        if (payload.length > kMaxPayloadSize) {
            throw new IOException("Payload quá lớn: " + payload.length + " > " + kMaxPayloadSize);
        }
        ByteBuffer header = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN);
        header.put(kCurrentKeyHeaderVersion);
        header.put(type);
        header.putInt(payload.length);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in, byte expectedType) throws IOException {
        byte[] headerBytes = new byte[kPairingPacketHeaderSize];
        in.readFully(headerBytes);
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        byte version = header.get();
        byte type = header.get();
        int size = header.getInt();
        if (version != kCurrentKeyHeaderVersion) {
            throw new IOException("Phiên bản pairing packet không khớp: " + version + " ≠ " + kCurrentKeyHeaderVersion);
        }
        if (type != expectedType) {
            throw new IOException("Loại packet không mong đợi: " + type + " ≠ " + expectedType);
        }
        if (size <= 0 || size > kMaxPayloadSize) {
            throw new IOException("Kích thước packet không hợp lệ: " + size);
        }
        byte[] payload = new byte[size];
        in.readFully(payload);
        return payload;
    }

    // ---- PeerInfo (exactly 8192 bytes: 1-byte type + 8191-byte data pad) ---

    private static byte[] buildPeerInfo(KeyPair keyPair, String deviceName) throws IOException {
        byte[] pubKeyBytes;
        try {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublicKey();
            pubKeyBytes = AndroidPubkey.encodeWithName(pub, deviceName);
        } catch (Exception e) {
            throw new IOException("Không encode được RSA pubkey: " + e.getMessage(), e);
        }
        if (pubKeyBytes.length > kMaxPeerInfoSize - 1) {
            throw new IOException("RSA pubkey quá lớn cho PeerInfo: " + pubKeyBytes.length);
        }
        byte[] peerInfo = new byte[kMaxPeerInfoSize];   // zero-initialized
        peerInfo[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY;   // type byte
        System.arraycopy(pubKeyBytes, 0, peerInfo, 1, pubKeyBytes.length);
        // remaining bytes stay zero (padding)
        return peerInfo;
    }

    // ---- TLS exported keying material via Conscrypt reflection ------------

    /**
     * Calls {@code com.android.org.conscrypt.Conscrypt.exportKeyingMaterial(
     * SSLSocket, String, byte[], int)} via reflection. This method is part of
     * the platform Conscrypt (the default SSL provider on Android 10+) but is
     * not in the public Android SDK, so we can't compile against it directly.
     *
     * <p>On Android 9 and earlier (or if the platform SSL isn't Conscrypt),
     * this returns null and pairing cannot proceed — but pairing requires
     * Android 11+ anyway, where this is always available.
     *
     * <p>The {@code label} parameter should include the trailing NUL byte
     * (matching AOSP {@code SSL_export_keying_material} semantics).
     */
    private static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length) {
        try {
            Class<?> conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt");
            // exportKeyingMaterial(SSLSocket, String, byte[], int)
            Method m = conscryptClass.getMethod("exportKeyingMaterial",
                javax.net.ssl.SSLSocket.class, String.class, byte[].class, int.class);
            return (byte[]) m.invoke(null, socket, label, context, length);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void quietClose(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }
}

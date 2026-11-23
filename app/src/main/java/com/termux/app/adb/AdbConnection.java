// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Adapted from Cameron Gutman's AdbLib (BSD-3-Clause) via the Muntashirakon
// libadb port. Minimal ADB client: opens a TCP socket, optionally upgrades to
// TLS 1.3 (A_STLS path used by adbd wireless debugging), sends CNXN, handles
// A_AUTH (sign the token + send the public key), and multiplexes streams.
package com.termux.app.adb;

import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

public final class AdbConnection implements java.io.Closeable {
    private static final String TAG = "AdbConnection";

    private final String mHost;
    private final int mPort;
    private final KeyPair mKeyPair;
    private final Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;
    private volatile int mMaxData = AdbProtocol.MAX_PAYLOAD;
    private volatile boolean mConnectionEstablished;
    private volatile boolean mConnectAttempted;
    private volatile boolean mAbortOnUnauthorised;
    private volatile boolean mAuthorisationFailed;
    private volatile Exception mConnectionException;
    private volatile boolean mIsTls;
    private final ConcurrentHashMap<Integer, AdbStream> mStreams = new ConcurrentHashMap<>();
    private int mLastLocalId;
    private final Thread mThread;
    private volatile String mDeviceName = "termux-adb";

    private AdbConnection(String host, int port, KeyPair keyPair) throws IOException {
        mHost = host;
        mPort = port;
        mKeyPair = keyPair;
        mSocket = new Socket(host, port);
        mSocket.setTcpNoDelay(true);
        mIn = mSocket.getInputStream();
        mOut = mSocket.getOutputStream();
        mThread = createThread();
    }

    public static AdbConnection create(String host, int port, PrivateKey privateKey, Certificate certificate) throws IOException {
        return new AdbConnection(host, port, new KeyPair(privateKey, certificate));
    }

    public void setDeviceName(String name) {
        mDeviceName = name;
    }

    public int getMaxData() {
        return mMaxData;
    }

    public boolean isConnectionEstablished() {
        return mConnectionEstablished;
    }

    public boolean connect() throws IOException, InterruptedException, AdbAuthenticationFailedException, AdbPairingRequiredException {
        return connect(Long.MAX_VALUE, TimeUnit.MILLISECONDS, false);
    }

    public boolean connect(long timeout, TimeUnit unit, boolean throwOnUnauthorised)
            throws IOException, InterruptedException, AdbAuthenticationFailedException, AdbPairingRequiredException {
        if (mConnectionEstablished) {
            throw new IllegalStateException("Already connected");
        }
        sendPacket(AdbProtocol.generateConnect());
        mConnectAttempted = true;
        mAbortOnUnauthorised = throwOnUnauthorised;
        mThread.start();
        synchronized (this) {
            long end = System.currentTimeMillis() + unit.toMillis(timeout);
            while (!mConnectionEstablished && mConnectAttempted && end - System.currentTimeMillis() > 0) {
                wait(end - System.currentTimeMillis());
            }
            if (!mConnectionEstablished) {
                if (mAuthorisationFailed) {
                    throw new AdbAuthenticationFailedException();
                }
                if (mConnectionException != null) {
                    String msg = mConnectionException.getMessage();
                    if (msg != null && msg.contains("protocol")) {
                        throw new AdbPairingRequiredException("ADB pairing is required.");
                    }
                    throw new IOException("Connection failed", mConnectionException);
                }
                return false;
            }
        }
        return true;
    }

    public AdbStream open(String destination) throws IOException, InterruptedException, AdbPairingRequiredException {
        if (!mConnectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        synchronized (this) {
            while (!mConnectionEstablished && mConnectAttempted) {
                wait();
            }
            if (!mConnectionEstablished) {
                throw new AdbPairingRequiredException();
            }
        }
        int localId = ++mLastLocalId;
        AdbStream stream = new AdbStream(this, localId);
        mStreams.put(localId, stream);
        sendPacket(AdbProtocol.generateOpen(localId, destination));
        synchronized (stream) {
            stream.wait();
        }
        if (stream.isClosed()) {
            mStreams.remove(localId);
            throw new IOException("Stream open rejected by peer.");
        }
        return stream;
    }

    void sendPacket(byte[] packet) throws IOException {
        synchronized (this) {
            mOut.write(packet);
            mOut.flush();
        }
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
        mThread.interrupt();
        try {
            mThread.join();
        } catch (InterruptedException ignored) {
        }
        for (AdbStream s : mStreams.values()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        mStreams.clear();
    }

    private Thread createThread() {
        return new Thread(() -> {
            boolean sentSignature = false;
            while (!Thread.interrupted()) {
                try {
                    AdbProtocol.Message msg = AdbProtocol.Message.parse(mIn);
                    switch (msg.command) {
                        case AdbProtocol.A_OKAY:
                        case AdbProtocol.A_WRTE:
                        case AdbProtocol.A_CLSE: {
                            if (!mConnectionEstablished) {
                                break;
                            }
                            AdbStream s = mStreams.get(msg.arg1);
                            if (s == null) {
                                break;
                            }
                            synchronized (s) {
                                if (msg.command == AdbProtocol.A_OKAY) {
                                    s.updateRemoteId(msg.arg0);
                                    s.readyForWrite();
                                    s.notifyAll();
                                } else if (msg.command == AdbProtocol.A_WRTE) {
                                    s.addPayload(msg.payload);
                                    s.sendReady();
                                } else {
                                    mStreams.remove(msg.arg1);
                                    s.notifyClose(true);
                                    s.notifyAll();
                                }
                            }
                            break;
                        }
                        case AdbProtocol.A_STLS: {
                            sendPacket(AdbProtocol.generateMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null));
                            SSLContext ctx = SslUtils.getSslContext(mKeyPair);
                            SSLSocket tls = (SSLSocket) ctx.getSocketFactory().createSocket(mSocket, mHost, mPort, true);
                            tls.startHandshake();
                            synchronized (this) {
                                mIn = tls.getInputStream();
                                mOut = tls.getOutputStream();
                                mIsTls = true;
                            }
                            break;
                        }
                        case AdbProtocol.A_AUTH: {
                            if (mIsTls || msg.arg0 != AdbProtocol.ADB_AUTH_TOKEN) {
                                break;
                            }
                            byte[] packet;
                            if (sentSignature) {
                                if (mAbortOnUnauthorised) {
                                    mAuthorisationFailed = true;
                                    return;
                                }
                                packet = AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_RSAPUBLICKEY,
                                        AndroidPubkey.encodeWithName((RSAPublicKey) mKeyPair.getPublicKey(), mDeviceName));
                            } else {
                                packet = AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_SIGNATURE,
                                        AndroidPubkey.adbAuthSign(mKeyPair.getPrivateKey(), msg.payload));
                                sentSignature = true;
                            }
                            sendPacket(packet);
                            break;
                        }
                        case AdbProtocol.A_CNXN: {
                            synchronized (this) {
                                mMaxData = Math.min(mMaxData, msg.arg1 > 0 ? msg.arg1 : mMaxData);
                                mConnectionEstablished = true;
                                notifyAll();
                            }
                            break;
                        }
                        default:
                            Log.w(TAG, "Unrecognized command 0x" + Integer.toHexString(msg.command));
                    }
                } catch (Exception e) {
                    mConnectionException = e;
                    synchronized (this) {
                        notifyAll();
                    }
                    return;
                }
            }
        });
    }
}

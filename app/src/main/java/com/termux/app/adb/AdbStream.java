// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Adapted from Cameron Gutman's AdbLib (BSD-3-Clause). Minimal ADB stream:
// a read queue fed by the connection thread's WRTE packets, write that splits
// into MAX_PAYLOAD chunks, and close that sends CLSE.
package com.termux.app.adb;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AdbStream {
    private final AdbConnection mConnection;
    private final int mLocalId;
    private volatile int mRemoteId;
    private final AtomicBoolean mWriteReady = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<byte[]> mReadQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean mClosed;

    AdbStream(AdbConnection connection, int localId) {
        mConnection = connection;
        mLocalId = localId;
    }

    void updateRemoteId(int remoteId) {
        mRemoteId = remoteId;
    }

    void readyForWrite() {
        mWriteReady.set(true);
    }

    void addPayload(byte[] payload) {
        mReadQueue.add(payload);
        synchronized (mReadQueue) {
            mReadQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        mConnection.sendPacket(AdbProtocol.generateReady(mLocalId, mRemoteId));
    }

    void notifyClose(boolean byPeer) {
        mClosed = true;
        synchronized (mReadQueue) {
            mReadQueue.notifyAll();
        }
    }

    public int read(byte[] buf, int off, int len) throws IOException {
        synchronized (mReadQueue) {
            byte[] data;
            while ((data = mReadQueue.poll()) == null && !mClosed) {
                try {
                    mReadQueue.wait();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (data == null) {
                return -1;
            }
            int n = Math.min(len, data.length);
            System.arraycopy(data, 0, buf, off, n);
            return n;
        }
    }

    public void write(byte[] data, int off, int len) throws IOException {
        synchronized (this) {
            while (!mClosed && !mWriteReady.compareAndSet(true, false)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (mClosed) {
                throw new IOException("Stream closed");
            }
        }
        int max = mConnection.getMaxData();
        while (len > 0) {
            int chunk = Math.min(len, max);
            mConnection.sendPacket(AdbProtocol.generateWrite(mLocalId, mRemoteId, data, off, chunk));
            off += chunk;
            len -= chunk;
        }
    }

    public void close() throws IOException {
        if (mClosed) {
            return;
        }
        notifyClose(false);
        mConnection.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
    }

    public boolean isClosed() {
        return mClosed;
    }
}

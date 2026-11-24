// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground service that holds an AdbConnection alive in the background,
 * surviving app exit — mirrors Shizuku's "service running" notification +
 * auto-reconnect behaviour.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>WirelessAdbActivity calls startWithConnection after a successful connect.</li>
 *   <li>The service promotes itself to foreground with a persistent notification.</li>
 *   <li>The connection is held in a static field so the activity (and other
 *       callers like the CLI BroadcastReceiver) can access it.</li>
 *   <li>If the connection drops, the service attempts to reconnect with
 *       exponential backoff (1s, 2s, 4s, 8s, max 30s).</li>
 *   <li>WirelessAdbActivity calls stop on disconnect, or the user taps the
 *       notification's "Dừng" action.</li>
 * </ul>
 *
 * <p>CLI integration: AdbCommandReceiver (registered in AndroidManifest)
 * listens for com.termux.adb.RUN_COMMAND broadcasts, reads the command extra,
 * runs it on the held AdbConnection, and writes the output to the file
 * specified in the outfile extra. The termux-adb shell script uses this.
 */
public class AdbService extends Service {

    public static final String ACTION_START = "com.termux.adb.action.START";
    public static final String ACTION_STOP  = "com.termux.adb.action.STOP";
    public static final String EXTRA_HOST   = "com.termux.adb.extra.HOST";
    public static final String EXTRA_PORT   = "com.termux.adb.extra.PORT";

    public static final String ACTION_RUN_COMMAND = "com.termux.adb.RUN_COMMAND";
    public static final String EXTRA_COMMAND      = "com.termux.adb.extra.COMMAND";
    public static final String EXTRA_OUTFILE      = "com.termux.adb.extra.OUTFILE";

    private static final String CHANNEL_ID = "adb_service";
    private static final int NOTIFICATION_ID = 0xADB;

    private static final AtomicReference<AdbConnection> sConnection = new AtomicReference<>();
    private static volatile AdbKeyManager sKeyManager;
    private static volatile String sHost;
    private static volatile int sPort;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private BroadcastReceiver mCommandReceiver;
    private boolean mReconnecting = false;

    public static void startWithConnection(Context ctx, AdbKeyManager km,
                                           String host, int port, AdbConnection conn) {
        sKeyManager = km;
        sHost = host;
        sPort = port;
        sConnection.set(conn);
        Intent i = new Intent(ctx, AdbService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_HOST, host);
        i.putExtra(EXTRA_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, AdbService.class).setAction(ACTION_STOP));
    }

    @Nullable
    public static AdbConnection getConnection() {
        return sConnection.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mCommandReceiver = new AdbCommandReceiver();
        IntentFilter filter = new IntentFilter(ACTION_RUN_COMMAND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mCommandReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mCommandReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdownConnection();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("ADB service đang chạy"));
        if (sConnection.get() == null && sKeyManager != null && sPort > 0) {
            reconnect();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mCommandReceiver != null) {
            try { unregisterReceiver(mCommandReceiver); } catch (Exception ignored) {}
            mCommandReceiver = null;
        }
        shutdownConnection();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void shutdownConnection() {
        AdbConnection conn = sConnection.getAndSet(null);
        if (conn != null) {
            mExecutor.execute(() -> {
                try { conn.close(); } catch (IOException ignored) {}
            });
        }
    }

    private void reconnect() {
        if (mReconnecting) return;
        mReconnecting = true;
        mExecutor.execute(() -> {
            int backoff = 1;
            while (mReconnecting && sConnection.get() == null) {
                try {
                    AdbKeyManager km = sKeyManager;
                    if (km == null) break;
                    KeyPair kp = km.getKeyPair();
                    AdbConnection conn = AdbConnection.create(sHost, sPort,
                        kp.getPrivateKey(), kp.getCertificate(), Build.VERSION.SDK_INT);
                    conn.connect(15, TimeUnit.SECONDS, false);
                    sConnection.set(conn);
                    mMain.post(() -> updateNotification("ADB service đang chạy (đã kết nối lại)"));
                    break;
                } catch (Exception e) {
                    // Capture backoff into a final local for the lambda —
                    // Java requires lambda-captured variables to be final
                    // or effectively final, but backoff is reassigned below.
                    final int currentBackoff = backoff;
                    mMain.post(() -> updateNotification("Đang thử kết nối lại… (lần sau " + currentBackoff + "s)"));
                    try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ie) { break; }
                    backoff = Math.min(backoff * 2, 30);
                }
            }
            mReconnecting = false;
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ADB Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Giữ kết nối ADB không dây chạy ngầm");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, AdbService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, WirelessAdbActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Termux ADB")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Dừng", stopPi)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    public static class AdbCommandReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_RUN_COMMAND.equals(intent.getAction())) return;
            final String command = intent.getStringExtra(EXTRA_COMMAND);
            final String outfile = intent.getStringExtra(EXTRA_OUTFILE);
            if (TextUtils.isEmpty(command) || TextUtils.isEmpty(outfile)) return;

            final AdbConnection conn = sConnection.get();
            if (conn == null) {
                writeOutput(outfile, "Lỗi: ADB service chưa chạy. Mở Termux → ADB không dây → KẾT NỐI trước.\n");
                return;
            }
            new Thread(() -> {
                try {
                    AdbStream stream = conn.open(command);
                    byte[] buf = new byte[8192];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while ((n = stream.read(buf, 0, buf.length)) >= 0) {
                        if (n > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                    stream.close();
                    writeOutput(outfile, sb.toString());
                } catch (Exception e) {
                    writeOutput(outfile, "Lỗi: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
                }
            }, "adb-cli").start();
        }

        private static void writeOutput(String path, String content) {
            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }
    }
}

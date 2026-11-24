// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Shell console that runs commands as UID 2000 (shell) via Shizuku.
 *
 * <p>Replaces the native ADB client (WirelessAdbActivity + AdbService +
 * PairingClient + SPAKE2 + TLS) with a much simpler integration:
 * the user installs the Shizuku app, starts it (via root or wireless
 * debugging), and this activity runs shell commands through Shizuku's
 * binder — no SPAKE2, no TLS handshake, no am broadcast, no phantom
 * process killer issues.
 *
 * <p>Prerequisites:
 * <ol>
 *   <li>Install Shizuku from https://shizuku.rikka.app/ or F-Droid</li>
 *   <li>Start Shizuku (Wireless debugging or Root)</li>
 *   <li>Open this activity → grant permission when prompted</li>
 * </ol>
 */
public class ShizukuShellActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION = 0x5A01;

    private TextView mStatusText;
    private EditText mCommandInput;
    private Button mRunBtn;
    private Button mQuickIdBtn;
    private Button mQuickPmBtn;
    private Button mQuickLogcatBtn;
    private TextView mOutput;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shizuku_shell);

        mStatusText = findViewById(R.id.shizuku_status);
        mCommandInput = findViewById(R.id.shizuku_command);
        mRunBtn = findViewById(R.id.shizuku_run_btn);
        mOutput = findViewById(R.id.shizuku_output);

        mOutput.setMovementMethod(new ScrollingMovementMethod());
        mRunBtn.setOnClickListener(v -> runCommand());

        // Quick command buttons
        mQuickIdBtn = findViewById(R.id.shizuku_quick_id);
        mQuickPmBtn = findViewById(R.id.shizuku_quick_pm);
        mQuickLogcatBtn = findViewById(R.id.shizuku_quick_logcat);
        if (mQuickIdBtn != null) mQuickIdBtn.setOnClickListener(v -> { mCommandInput.setText("id -u"); runCommand(); });
        if (mQuickPmBtn != null) mQuickPmBtn.setOnClickListener(v -> { mCommandInput.setText("pm list packages -3 | head -20"); runCommand(); });
        if (mQuickLogcatBtn != null) mQuickLogcatBtn.setOnClickListener(v -> { mCommandInput.setText("logcat -d -t 50"); runCommand(); });

        // Shizuku binder listener — update status when binder becomes available
        Shizuku.OnBinderReceivedListener binderListener = () -> mMain.post(this::updateStatus);
        Shizuku.addBinderReceivedListener(binderListener, getMainLooper());

        // Permission result listener
        Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
            if (requestCode == REQUEST_PERMISSION) {
                mMain.post(this::updateStatus);
            }
        };
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (!isShizukuInstalled()) {
            mStatusText.setText("Shizuku chưa cài. Tải từ shizuku.rikka.app");
            mRunBtn.setEnabled(false);
            // Offer to open Play Store / website
            mStatusText.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/")));
                } catch (Exception ignored) {}
            });
            return;
        }

        mStatusText.setOnClickListener(null);

        if (!Shizuku.pingBinder()) {
            mStatusText.setText("Shizuku chưa chạy. Mở app Shizuku → Start (Wireless debugging hoặc Root).");
            mRunBtn.setEnabled(false);
            return;
        }

        if (!hasPermission()) {
            mStatusText.setText("Shizuku đang chạy nhưng chưa cấp quyền. Đang yêu cầu…");
            mRunBtn.setEnabled(false);
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                new AlertDialog.Builder(this)
                    .setTitle("Cần quyền Shizuku")
                    .setMessage("Termux cần quyền Shizuku để chạy lệnh shell với UID 2000 (shell).")
                    .setPositiveButton("Cấp quyền", (d, w) -> Shizuku.requestPermission(REQUEST_PERMISSION))
                    .setNegativeButton("Hủy", null)
                    .show();
            } else {
                Shizuku.requestPermission(REQUEST_PERMISSION);
            }
            return;
        }

        mStatusText.setText("Shizuku đang chạy ✓ — lệnh sẽ chạy với UID shell (2000)");
        mRunBtn.setEnabled(true);
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getApplicationInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void appendOutput(final String text) {
        mMain.post(() -> mOutput.append(text));
    }

    private void runCommand() {
        final String cmd = mCommandInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        if (!hasPermission()) {
            Toast.makeText(this, "Chưa có quyền Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }

        mRunBtn.setEnabled(false);
        appendOutput("$ " + cmd + "\n");

        new Thread(() -> {
            try {
                // Shizuku.newProcess runs the command as UID 2000 (shell)
                Process process = Shizuku.newProcess(
                    new String[]{"sh", "-c", cmd}, null, null);

                InputStream is = process.getInputStream();
                InputStream es = process.getErrorStream();
                byte[] buf = new byte[4096];
                int n;

                // Read stdout
                while ((n = is.read(buf)) > 0) {
                    appendOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                // Read stderr
                while ((n = es.read(buf)) > 0) {
                    appendOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    appendOutput("[exit code: " + exitCode + "]\n");
                }
            } catch (Exception e) {
                appendOutput("Lỗi: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            } finally {
                mMain.post(() -> mRunBtn.setEnabled(true));
            }
        }, "shizuku-shell").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shizuku listeners are static — don't remove them here to avoid
        // crashes if the activity is recreated quickly. They're lightweight.
    }
}

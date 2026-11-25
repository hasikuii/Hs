// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

public class ShizukuShellActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION = 0x5A01;

    private TextView mStatusText;
    private EditText mCommandInput;
    private Button mRunBtn;
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

        Button quickId = findViewById(R.id.shizuku_quick_id);
        Button quickPm = findViewById(R.id.shizuku_quick_pm);
        Button quickLogcat = findViewById(R.id.shizuku_quick_logcat);
        if (quickId != null) quickId.setOnClickListener(v -> { mCommandInput.setText("id -u"); runCommand(); });
        if (quickPm != null) quickPm.setOnClickListener(v -> { mCommandInput.setText("pm list packages -3 | head -20"); runCommand(); });
        if (quickLogcat != null) quickLogcat.setOnClickListener(v -> { mCommandInput.setText("logcat -d -t 50"); runCommand(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (!isShizukuInstalled()) {
            mStatusText.setText("Shizuku chưa cài. Bấm để tải từ shizuku.rikka.app");
            mRunBtn.setEnabled(false);
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
            mStatusText.setText("Shizuku chưa chạy. Mở app Shizuku → Start.");
            mRunBtn.setEnabled(false);
            return;
        }

        if (!hasPermission()) {
            mStatusText.setText("Shizuku chạy nhưng chưa cấp quyền. Đang yêu cầu…");
            mRunBtn.setEnabled(false);
            try {
                Shizuku.requestPermission(REQUEST_PERMISSION);
            } catch (Exception ignored) {}
            return;
        }

        mStatusText.setText("Shizuku đang chạy ✓ — UID shell (2000)");
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
            // checkSelfPermission() returns int (PERMISSION_GRANTED=0 or PERMISSION_DENIED=-1)
            // in Shizuku API v13.1.5. No isPreV11() branching needed.
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
                Process process = Shizuku.newProcess(
                    new String[]{"sh", "-c", cmd}, null, null);

                InputStream is = process.getInputStream();
                InputStream es = process.getErrorStream();
                byte[] buf = new byte[4096];
                int n;

                while ((n = is.read(buf)) > 0) {
                    appendOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
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
}

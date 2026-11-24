// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku-style on-device wireless ADB debugging entry point.
 *
 * <p>Flow:
 * <ol>
 *   <li>Pair once: open Android 11+ Wireless debugging → "Pair device with pairing code",
 *       enter the port + 6-digit code here.</li>
 *   <li>Connect: enter the connection port from the Wireless debugging main screen.</li>
 *   <li>Run shell commands with UID 2000 (shell) — more privileges than a normal app
 *       (dumpsys, pm grant, logcat, etc.).</li>
 * </ol>
 *
 * <p>Requires Android 11+ (API 30) for TLS 1.3 + wireless debugging pairing.
 */
public class WirelessAdbActivity extends AppCompatActivity {

    private static final String HOST = "127.0.0.1";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private AdbKeyManager mKeyManager;
    private AdbConnection mConnection;

    private TextView mStatusText;
    private TextView mFingerprintText;
    private EditText mPairPort;
    private EditText mPairCode;
    private Button mPairBtn;
    private EditText mConnectPort;
    private Button mConnectBtn;
    private Button mDisconnectBtn;
    private EditText mCommandInput;
    private Button mRunBtn;
    private TextView mOutput;
    private LinearLayout mSdkWarning;

    private boolean mAdbSupported;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wireless_adb);

        mStatusText = findViewById(R.id.adb_status);
        mFingerprintText = findViewById(R.id.adb_fingerprint);
        mPairPort = findViewById(R.id.adb_pair_port);
        mPairCode = findViewById(R.id.adb_pair_code);
        mPairBtn = findViewById(R.id.adb_pair_btn);
        mConnectPort = findViewById(R.id.adb_connect_port);
        mConnectBtn = findViewById(R.id.adb_connect_btn);
        mDisconnectBtn = findViewById(R.id.adb_disconnect_btn);
        mCommandInput = findViewById(R.id.adb_command);
        mRunBtn = findViewById(R.id.adb_run_btn);
        mOutput = findViewById(R.id.adb_output);
        mSdkWarning = findViewById(R.id.adb_sdk_warning);

        mOutput.setMovementMethod(new ScrollingMovementMethod());

        mKeyManager = new AdbKeyManager(this, "termux-adb");

        mAdbSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        mSdkWarning.setVisibility(mAdbSupported ? View.GONE : View.VISIBLE);
        mConnectBtn.setEnabled(mAdbSupported);
        mPairBtn.setEnabled(false);

        TextWatcher pairWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updatePairButtonState();
            }
        };
        mPairPort.addTextChangedListener(pairWatcher);
        mPairCode.addTextChangedListener(pairWatcher);

        View.OnFocusChangeListener focusValidator = (v, hasFocus) -> {
            if (!hasFocus) updatePairButtonState();
        };
        mPairPort.setOnFocusChangeListener(focusValidator);
        mPairCode.setOnFocusChangeListener(focusValidator);

        updatePairButtonState();

        mExecutor.execute(() -> {
            try {
                final String fp = mKeyManager.getFingerprint();
                mMain.post(() -> mFingerprintText.setText("Khóa ADB (SHA-256): " + fp));
            } catch (final Exception e) {
                mMain.post(() -> mFingerprintText.setText("Lỗi sinh khóa: " + e.getMessage()));
            }
        });

        mPairBtn.setOnClickListener(v -> doPair());
        mConnectBtn.setOnClickListener(v -> doConnect());
        mDisconnectBtn.setOnClickListener(v -> doDisconnect());
        mRunBtn.setOnClickListener(v -> runCommand());

        findViewById(R.id.adb_quick_id).setOnClickListener(v -> { mCommandInput.setText("id -u"); runCommand(); });
        findViewById(R.id.adb_quick_packages).setOnClickListener(v -> { mCommandInput.setText("pm list packages -3 | head -20"); runCommand(); });
        findViewById(R.id.adb_quick_logcat).setOnClickListener(v -> { mCommandInput.setText("logcat -d -t 50"); runCommand(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePairButtonState();
    }

    private void updatePairButtonState() {
        try {
            String port = mPairPort.getText() == null ? ""
                : mPairPort.getText().toString().trim();
            String code = mPairCode.getText() == null ? ""
                : mPairCode.getText().toString().trim();
            String codeDigits = code.replaceAll("\\D", "");
            mPairBtn.setEnabled(mAdbSupported && !port.isEmpty() && codeDigits.length() == 6);
        } catch (Exception ignored) {
        }
    }

    private void setStatus(final String text) {
        mMain.post(() -> mStatusText.setText(text));
    }

    private void appendOutput(final String text) {
        mMain.post(() -> {
            mOutput.append(text);
            if (!text.endsWith("\n")) {
                mOutput.append("\n");
            }
        });
    }

    private void doPair() {
        final String portStr = mPairPort.getText() == null ? "" : mPairPort.getText().toString().trim();
        final String code = mPairCode.getText() == null ? "" : mPairCode.getText().toString().trim();
        if (portStr.isEmpty()) {
            Toast.makeText(this, "Nhập cổng ghép nối", Toast.LENGTH_SHORT).show();
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Cổng không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }

        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("Đang ghép nối")
            .setMessage("Kết nối TLS + SPAKE2 tới " + HOST + ":" + port + "…\n"
                + "Nếu mã đúng, khóa công khai của app sẽ được thêm vào adb_keys.")
            .setCancelable(false)
            .show();

        mPairBtn.setEnabled(false);
        mExecutor.execute(() -> {
            String okMsg = null;
            String errMsg = null;
            try {
                KeyPair kp = mKeyManager.getKeyPair();
                PairingClient client = new PairingClient(HOST, kp, "termux-adb");
                client.pair(port, code, 15);
                okMsg = "Đã ghép nối thành công. Khóa công khai đã được ủy quyền.\n"
                    + "Giờ nhập cổng kết nối (màn Wireless debugging chính) rồi bấm KẾT NỐI.";
            } catch (Throwable t) {
                errMsg = "Ghép nối thất bại: " + t.getMessage();
            }
            final String finalOk = okMsg;
            final String finalErr = errMsg;
            mMain.post(() -> {
                if (progress.isShowing()) {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                }
                updatePairButtonState();
                if (finalOk != null) {
                    new AlertDialog.Builder(WirelessAdbActivity.this)
                        .setTitle("Ghép nối xong")
                        .setMessage(finalOk)
                        .setPositiveButton("Đã hiểu", null)
                        .show();
                } else {
                    new AlertDialog.Builder(WirelessAdbActivity.this)
                        .setTitle("Ghép nối thất bại")
                        .setMessage(finalErr + "\n\n"
                            + "Nguyên nhân thường gặp:\n"
                            + "• Mã hoặc cổng sai (cổng ghép nối ≠ cổng kết nối)\n"
                            + "• Mã hết hạn (mỗi mã chỉ dùng 1 lần trong ~30s)\n"
                            + "• Wi-Fi khác mạng với máy cần ghép\n"
                            + "• Android < 11 không hỗ trợ")
                        .setPositiveButton("Thử lại", null)
                        .show();
                }
            });
        });
    }

    private void doConnect() {
        if (mConnection != null) {
            Toast.makeText(this, "Đã kết nối rồi", Toast.LENGTH_SHORT).show();
            return;
        }
        final String portStr = mConnectPort.getText().toString().trim();
        if (portStr.isEmpty()) {
            Toast.makeText(this, "Nhập cổng kết nối", Toast.LENGTH_SHORT).show();
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Cổng không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }
        mConnectBtn.setEnabled(false);
        setStatus("Đang kết nối TLS 1.3 tới " + HOST + ":" + port + "…");
        mExecutor.execute(() -> {
            try {
                KeyPair kp = mKeyManager.getKeyPair();
                AdbConnection conn = AdbConnection.create(HOST, port, kp.getPrivateKey(), kp.getCertificate(), Build.VERSION.SDK_INT);
                conn.connect(15, TimeUnit.SECONDS, false);
                mConnection = conn;
                AdbService.startWithConnection(WirelessAdbActivity.this, mKeyManager, HOST, port, conn);
                setStatus("Đã kết nối. Shell UID = 2000 (shell). Chạy lệnh bên dưới.\nService chạy ngầm — thoát app vẫn giữ kết nối.");
                mMain.post(() -> {
                    mConnectBtn.setEnabled(false);
                    mDisconnectBtn.setEnabled(true);
                    mRunBtn.setEnabled(true);
                    Toast.makeText(WirelessAdbActivity.this, "Đã kết nối ADB", Toast.LENGTH_SHORT).show();
                });
            } catch (final Exception e) {
                setStatus("Kết nối thất bại: " + e.getMessage());
                mMain.post(() -> mConnectBtn.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R));
            }
        });
    }

    private void doDisconnect() {
        if (mConnection == null && AdbService.getConnection() == null) {
            return;
        }
        AdbService.stop(this);
        final AdbConnection conn = mConnection;
        mConnection = null;
        if (conn != null) {
            mExecutor.execute(() -> {
                try {
                    conn.close();
                } catch (IOException ignored) {
                }
                setStatus("Đã ngắt kết nối.");
                mMain.post(() -> {
                    mConnectBtn.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                    mDisconnectBtn.setEnabled(false);
                    mRunBtn.setEnabled(false);
                });
            });
        } else {
            setStatus("Đã ngắt kết nối.");
            mMain.post(() -> {
                mConnectBtn.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                mDisconnectBtn.setEnabled(false);
                mRunBtn.setEnabled(false);
            });
        }
    }

    private void runCommand() {
        final AdbConnection conn = mConnection != null ? mConnection : AdbService.getConnection();
        if (conn == null) {
            Toast.makeText(this, "Chưa kết nối", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cmd = mCommandInput.getText().toString().trim();
        if (cmd.isEmpty()) {
            return;
        }
        mRunBtn.setEnabled(false);
        appendOutput("$ " + cmd);
        mExecutor.execute(() -> {
            try {
                AdbStream stream = conn.open("shell:" + cmd);
                byte[] buf = new byte[4096];
                int n;
                while ((n = stream.read(buf, 0, buf.length)) >= 0) {
                    if (n > 0) {
                        appendOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                stream.close();
            } catch (final Exception e) {
                appendOutput("Lỗi: " + e.getMessage());
            } finally {
                mMain.post(() -> mRunBtn.setEnabled(true));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConnection != null && AdbService.getConnection() == null) {
            final AdbConnection conn = mConnection;
            mExecutor.execute(() -> {
                try { conn.close(); } catch (IOException ignored) {}
            });
        }
        mConnection = null;
        mExecutor.shutdownNow();
    }
}

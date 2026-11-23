package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File-management utilities shared by the workspace menu.
 *
 * Provides:
 *  - Persistent user preferences (sort mode, "show hidden" flag).
 *  - A most-recently-visited directories list (ordered, deduplicated, capped).
 *  - Storage statistics for the filesystem backing a given directory.
 *  - File hash calculation (MD5, SHA-1, SHA-256) with a progress dialog helper.
 *  - A "touch" helper that bumps {@code lastModified} to now.
 *  - An {@code ACTION_SEND} share helper that routes files through the workspace
 *    {@link FileProvider} so other apps can read them without storage permissions.
 *
 * The class is intentionally self-contained so that callers in
 * {@code WorkspaceMenuBottomSheet} and {@code WorkspaceArchiveDialogs} can use
 * individual features without dragging in unrelated dependencies.
 */
public final class WorkspaceFileTools {

    private static final String PREFS_NAME = "workspace_file_manager";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_SHOW_HIDDEN = "show_hidden";
    private static final String KEY_RECENT_DIRS = "recent_dirs";
    private static final String KEY_RECENT_DIRS_ORDER = "recent_dirs_order";
    private static final int MAX_RECENT_DIRS = 12;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WorkspaceFileTools() {}

    // ---------------------------------------------------------------- preferences

    public static int getSortModePref(@NonNull Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SORT_MODE, 0);
    }

    public static void setSortModePref(@NonNull Context context, int mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SORT_MODE, mode).apply();
    }

    public static boolean getShowHiddenPref(@NonNull Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HIDDEN, false);
    }

    public static void setShowHiddenPref(@NonNull Context context, boolean value) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_HIDDEN, value).apply();
    }

    // ---------------------------------------------------------------- recent dirs

    /**
     * Returns the most-recently-visited directories in MRU order, filtering out
     * any entries that no longer exist or are not directories.
     */
    @NonNull
    public static List<File> getRecentDirectories(@NonNull Context context) {
        String ordered = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_DIRS_ORDER, "");
        List<File> result = new ArrayList<>();
        if (ordered != null && !ordered.isEmpty()) {
            for (String p : ordered.split("\n")) {
                if (p.isEmpty()) continue;
                File f = new File(p);
                if (f.exists() && f.isDirectory()) result.add(f);
            }
        } else {
            // Fallback for the older (unordered) representation.
            Set<String> stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_RECENT_DIRS, java.util.Collections.emptySet());
            if (stored != null) {
                for (String p : stored) {
                    File f = new File(p);
                    if (f.exists() && f.isDirectory()) result.add(f);
                }
            }
        }
        return result;
    }

    /**
     * Records {@code dir} as the most recently visited directory, moving it to
     * the front of the list (de-duplicating any previous occurrence) and
     * trimming the list to {@link #MAX_RECENT_DIRS}.
     */
    public static void recordRecentDir(@NonNull Context context, @NonNull File dir) {
        if (dir == null || !dir.isDirectory()) return;
        String path = dir.getAbsolutePath();
        List<String> ordered = new ArrayList<>();
        for (File f : getRecentDirectories(context)) {
            String p = f.getAbsolutePath();
            if (!p.equals(path)) ordered.add(p);
        }
        ordered.add(0, path);
        while (ordered.size() > MAX_RECENT_DIRS) ordered.remove(ordered.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(ordered.get(i));
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_DIRS_ORDER, sb.toString())
            .putStringSet(KEY_RECENT_DIRS, new LinkedHashSet<>(ordered))
            .apply();
    }

    public static void clearRecentDirectories(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECENT_DIRS)
            .remove(KEY_RECENT_DIRS_ORDER)
            .apply();
    }

    // ---------------------------------------------------------------- storage

    /** Returns {@code long[3] = { total bytes, free bytes, used bytes }}. */
    @NonNull
    public static long[] getStorageStats(@NonNull File dir) {
        long total = dir.getTotalSpace();
        long free = dir.getFreeSpace();
        long used = total > 0 ? total - free : 0L;
        return new long[] { total, free, used };
    }

    // ---------------------------------------------------------------- hashing

    @NonNull
    public static String computeFileHash(@NonNull File file, @NonNull String algorithm)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return toHex(md.digest());
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------- touch

    /** Sets the file's last-modified time to the current system time. */
    public static boolean touchNow(@NonNull File file) {
        long now = System.currentTimeMillis();
        return file.setLastModified(now);
    }

    // ---------------------------------------------------------------- share

    /**
     * Shares a single file via {@link Intent#ACTION_SEND} using the workspace
     * {@link FileProvider}, granting read access to the chosen target app.
     */
    public static void shareFile(@NonNull Activity activity, @NonNull File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".workspace.fileprovider", file);
            String mime = guessMimeType(file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime != null ? mime : "*/*");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Chia sẻ \"" + file.getName() + "\""));
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity, "Không thể chia sẻ tệp này qua FileProvider",
                Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, "Không tìm thấy ứng dụng nào để chia sẻ",
                Toast.LENGTH_SHORT).show();
        }
    }

    private static String guessMimeType(@NonNull File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "*/*";
    }

    // ---------------------------------------------------------------- dialogs

    /** Shows a simple dialog with total / used / free space for the filesystem of {@code dir}. */
    public static void showStorageDialog(@NonNull Context context, @NonNull File dir) {
        long[] stats = getStorageStats(dir);
        long total = stats[0];
        long free = stats[1];
        long used = stats[2];
        int usedPct = total > 0 ? (int) (used * 100 / total) : 0;
        String msg = "Thư mục hiện tại:\n" + dir.getAbsolutePath() + "\n\n"
            + "Tổng dung lượng phân vùng: " + formatSize(total) + "\n"
            + "Đã sử dụng: " + formatSize(used) + " (" + usedPct + "%)\n"
            + "Còn trống: " + formatSize(free);
        new AlertDialog.Builder(context)
            .setTitle("Thông tin ổ đĩa")
            .setMessage(msg)
            .setPositiveButton("Đóng", null)
            .show();
    }

    /** Callback used by {@link #showRecentDirsDialog(Activity, RecentDirCallback)}. */
    public interface RecentDirCallback {
        void onSelected(@NonNull File dir);
    }

    /**
     * Shows a chooser with the most-recently-visited directories. Tapping an
     * entry invokes {@code callback}; the neutral button clears the list.
     */
    public static void showRecentDirsDialog(@NonNull Activity activity,
                                            @NonNull RecentDirCallback callback) {
        List<File> recents = getRecentDirectories(activity);
        if (recents.isEmpty()) {
            Toast.makeText(activity, "Chưa có thư mục nào được mở gần đây",
                Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[recents.size()];
        for (int i = 0; i < recents.size(); i++) {
            labels[i] = recents.get(i).getAbsolutePath();
        }
        new AlertDialog.Builder(activity)
            .setTitle("Thư mục gần đây")
            .setItems(labels, (d, which) -> callback.onSelected(recents.get(which)))
            .setNeutralButton("Xóa danh sách", (d, w) -> {
                clearRecentDirectories(activity);
                Toast.makeText(activity, "Đã xóa danh sách thư mục gần đây",
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Đóng", null)
            .show();
    }

    /**
     * Computes MD5, SHA-1 and SHA-256 for the given file in a background thread
     * and shows the digests in a dialog, with copy-to-clipboard buttons.
     */
    public static void showHashDialog(@NonNull Activity activity, @NonNull File file) {
        if (!file.isFile()) {
            Toast.makeText(activity, "Chỉ hỗ trợ tính mã băm cho tệp tin",
                Toast.LENGTH_SHORT).show();
            return;
        }
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Đang tính mã băm");
        progress.setMessage(file.getName());
        progress.setIndeterminate(true);
        progress.setCancelable(true);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        progress.setOnCancelListener(d -> cancelled.set(true));
        progress.show();

        EXECUTOR.execute(() -> {
            String md5 = null;
            String sha1 = null;
            String sha256 = null;
            String error = null;
            try {
                if (!cancelled.get()) md5 = computeFileHash(file, "MD5");
                if (!cancelled.get()) sha1 = computeFileHash(file, "SHA-1");
                if (!cancelled.get()) sha256 = computeFileHash(file, "SHA-256");
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String fMd5 = md5;
            final String fSha1 = sha1;
            final String fSha256 = sha256;
            final String fError = error;
            MAIN.post(() -> {
                if (activity.isFinishing()) return;
                if (progress.isShowing()) progress.dismiss();
                if (cancelled.get()) return;
                if (fError != null) {
                    Toast.makeText(activity, "Lỗi: " + fError, Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder msg = new StringBuilder();
                msg.append("Tệp: ").append(file.getName()).append('\n');
                msg.append("Kích thước: ").append(formatSize(file.length())).append("\n\n");
                msg.append("MD5:\n").append(fMd5).append("\n\n");
                msg.append("SHA-1:\n").append(fSha1).append("\n\n");
                msg.append("SHA-256:\n").append(fSha256);
                new AlertDialog.Builder(activity)
                    .setTitle("Mã băm tệp")
                    .setMessage(msg.toString())
                    .setPositiveButton("Sao chép SHA-256", (d, w) -> copyText(activity, fSha256))
                    .setNeutralButton("Sao chép MD5", (d, w) -> copyText(activity, fMd5))
                    .setNegativeButton("Đóng", null)
                    .show();
            });
        });
    }

    private static void copyText(@NonNull Context context, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("hash", text));
            Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

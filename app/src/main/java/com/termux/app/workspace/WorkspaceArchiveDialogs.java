package com.termux.app.workspace;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZArchiver/RAR-style dialogs for compression, archive inspection/extraction,
 * copy-to-destination picker, and recursive file/folder properties.
 */
public final class WorkspaceArchiveDialogs {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WorkspaceArchiveDialogs() {}

    public static void showCompressDialog(@NonNull Context context,
                                          @NonNull List<File> sources,
                                          @NonNull File outputDir,
                                          @NonNull Runnable onCompleted) {
        if (sources.isEmpty()) return;

        float d = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);

        String baseName = sources.size() == 1
            ? stripExt(sources.get(0).getName())
            : (outputDir.getName().isEmpty() ? "archive" : outputDir.getName());

        EditText nameInput = new EditText(context);
        nameInput.setText(baseName);
        nameInput.setSelectAllOnFocus(true);
        nameInput.setSingleLine(true);
        container.addView(nameInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RadioGroup formatGroup = new RadioGroup(context);
        formatGroup.setOrientation(RadioGroup.VERTICAL);
        formatGroup.setPadding(0, (int) (8 * d), 0, 0);

        RadioButton zipRb = new RadioButton(context);
        zipRb.setText("ZIP (.zip) — Phổ biến nhất");
        zipRb.setId(1001);

        RadioButton tarGzRb = new RadioButton(context);
        tarGzRb.setText("TAR.GZ (.tar.gz) — Nén chuẩn Linux / Termux");
        tarGzRb.setId(1002);

        RadioButton tarRb = new RadioButton(context);
        tarRb.setText("TAR (.tar) — Đóng gói không nén");
        tarRb.setId(1003);

        formatGroup.addView(zipRb);
        formatGroup.addView(tarGzRb);
        formatGroup.addView(tarRb);
        formatGroup.check(1001);
        container.addView(formatGroup);

        new AlertDialog.Builder(context)
            .setTitle(R.string.workspace_archive_compress_title)
            .setView(container)
            .setPositiveButton("Nén ngay", (dialog, which) -> {
                String rawName = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                if (rawName.isEmpty() || rawName.contains("/")) {
                    Toast.makeText(context, "Tên tệp nén không hợp lệ", Toast.LENGTH_SHORT).show();
                    return;
                }
                int checkedId = formatGroup.getCheckedRadioButtonId();
                final WorkspaceArchiveHelper.ArchiveFormat format = checkedId == 1002
                    ? WorkspaceArchiveHelper.ArchiveFormat.TAR_GZ
                    : (checkedId == 1003 ? WorkspaceArchiveHelper.ArchiveFormat.TAR : WorkspaceArchiveHelper.ArchiveFormat.ZIP);

                String fileName = rawName.toLowerCase(Locale.ROOT).endsWith(format.extension)
                    ? rawName
                    : rawName + format.extension;
                final File outArchive = WorkspaceArchiveHelper.resolveUniqueTarget(outputDir, fileName, false);

                startCompressWithProgress(context, sources, outArchive, format, onCompleted);
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    /**
     * ZArchiver-style progress dialog: percentage label, full file path,
     * "Giữ cho màn hình bật" checkbox (WakeLock), and "Ẩn" (Hide) button
     * that dismisses the dialog but keeps compression running in the
     * background. On completion, if any files were skipped, a separate
     * scrollable "Log lỗi chi tiết" dialog lists each skipped file's full
     * path + error message, with a "Sao chép log" button.
     */
    private static void startCompressWithProgress(@NonNull Context context,
                                                  @NonNull List<File> sources,
                                                  @NonNull File outArchive,
                                                  @NonNull WorkspaceArchiveHelper.ArchiveFormat format,
                                                  @NonNull Runnable onCompleted) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean dialogShown = new AtomicBoolean(false);
        final AtomicBoolean hidden = new AtomicBoolean(false);
        final List<String> errorLog = new ArrayList<>();

        float d = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout progressView = new LinearLayout(context);
        progressView.setOrientation(LinearLayout.VERTICAL);
        progressView.setPadding(pad, pad / 2, pad, 0);

        final ProgressBar bar = new ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressView.addView(bar, barLp);

        final TextView percentText = new TextView(context);
        percentText.setPadding(0, (int) (4 * d), 0, 0);
        percentText.setText("0%");
        progressView.addView(percentText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView statusText = new TextView(context);
        statusText.setPadding(0, (int) (8 * d), 0, 0);
        statusText.setText("Đang chuẩn bị…");
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        progressView.addView(statusText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final CheckBox keepScreenCb = new CheckBox(context);
        keepScreenCb.setText("Giữ cho màn hình bật");
        keepScreenCb.setPadding(0, (int) (8 * d), 0, 0);
        progressView.addView(keepScreenCb, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog progress = new AlertDialog.Builder(context)
            .setTitle("Đang nén: " + outArchive.getName())
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton("Hủy", (dialog, which) -> cancelled.set(true))
            .setNeutralButton("Ẩn", (dialog, which) -> {
                hidden.set(true);
                dialogShown.set(false);
                dialog.dismiss();
            })
            .show();
        dialogShown.set(true);

        final PowerManager.WakeLock[] wakeLockHolder = new PowerManager.WakeLock[1];
        keepScreenCb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (wakeLockHolder[0] == null) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wakeLockHolder[0] = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Termux:Compress");
                        wakeLockHolder[0].setReferenceCounted(false);
                    }
                }
                if (wakeLockHolder[0] != null) {
                    try { wakeLockHolder[0].acquire(); } catch (Exception ignored) {}
                }
            } else {
                if (wakeLockHolder[0] != null && wakeLockHolder[0].isHeld()) {
                    try { wakeLockHolder[0].release(); } catch (Exception ignored) {}
                }
            }
        });

        final WorkspaceArchiveHelper.CompressProgress callback = new WorkspaceArchiveHelper.CompressProgress() {
            @Override
            public void onProgress(long bytesWritten, long totalBytes, String currentFile) {
                if (!dialogShown.get()) return;
                MAIN.post(() -> {
                    if (!dialogShown.get() || !progress.isShowing()) return;
                    if (totalBytes > 0) {
                        int perMille = (int) Math.min(1000L, bytesWritten * 1000L / totalBytes);
                        bar.setProgress(perMille);
                        percentText.setText((perMille / 10) + "%");
                    }
                    statusText.setText(currentFile);
                });
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        EXECUTOR.execute(() -> {
            File writtenFile = null;
            int[] skippedOut = new int[]{0};
            Throwable error = null;
            try {
                writtenFile = WorkspaceArchiveHelper.compress(sources, outArchive, format, callback, skippedOut, errorLog);
            } catch (Throwable t) {
                error = t;
            }
            final File finalWritten = writtenFile;
            final int finalSkipped = skippedOut[0];
            final Throwable finalError = error;
            final List<String> finalErrorLog = new ArrayList<>(errorLog);
            if (wakeLockHolder[0] != null && wakeLockHolder[0].isHeld()) {
                try { wakeLockHolder[0].release(); } catch (Exception ignored) {}
            }
            MAIN.post(() -> {
                if (dialogShown.get() && progress.isShowing()) {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                }
                if (cancelled.get()) {
                    if (finalWritten != null && finalWritten.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        finalWritten.delete();
                    }
                    Toast.makeText(context, "Đã hủy nén", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (finalError != null) {
                    new AlertDialog.Builder(context)
                        .setTitle("Lỗi nén")
                        .setMessage(finalError.getClass().getSimpleName() + ": " + finalError.getMessage())
                        .setPositiveButton("Đóng", null)
                        .show();
                    return;
                }
                String sizeStr = finalWritten != null
                    ? WorkspaceArchiveHelper.formatSize(finalWritten.length()) : "?";
                String skippedNote = finalSkipped > 0
                    ? "\nĐã bỏ qua " + finalSkipped + " tệp không đọc được"
                    : "";
                String successMsg = "Đã nén thành: "
                    + (finalWritten != null ? finalWritten.getName() : "?")
                    + " (" + sizeStr + ")" + skippedNote;

                if (hidden.get()) {
                    Toast.makeText(context, successMsg, Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(context)
                        .setTitle("Nén xong")
                        .setMessage(successMsg)
                        .setPositiveButton("Đóng", null)
                        .show();
                }

                if (!finalErrorLog.isEmpty()) {
                    StringBuilder logBuilder = new StringBuilder();
                    logBuilder.append(finalErrorLog.size()).append(" tệp đã bị bỏ qua:\n\n");
                    int shown = 0;
                    for (String entry : finalErrorLog) {
                        if (shown >= 50) {
                            logBuilder.append("... và ").append(finalErrorLog.size() - 50).append(" tệp khác\n");
                            break;
                        }
                        logBuilder.append(entry).append("\n");
                        shown++;
                    }
                    final String logText = logBuilder.toString();
                    MAIN.postDelayed(() -> new AlertDialog.Builder(context)
                        .setTitle("Log lỗi chi tiết (" + finalErrorLog.size() + " tệp)")
                        .setView(createScrollableText(context, logText))
                        .setPositiveButton("Sao chép log", (dlg, w) -> {
                            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("compress_error_log", logText));
                                Toast.makeText(context, "Đã sao chép log", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Đóng", null)
                        .show(), 500);
                }

                onCompleted.run();
            });
        });
    }

    private static ScrollView createScrollableText(@NonNull Context context, @NonNull String text) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(context);
        sv.addView(tv);
        return sv;
    }

    public static void showArchiveQuickMenu(@NonNull Context context,
                                            @NonNull File archiveFile,
                                            @NonNull File currentDir,
                                            @NonNull Runnable onExtracted,
                                            @NonNull Runnable onOpenAsText) {
        String subFolderName = WorkspaceArchiveHelper.stripArchiveExtension(archiveFile.getName());
        String[] options = new String[] {
            context.getString(R.string.workspace_archive_view),
            context.getString(R.string.workspace_archive_extract_here),
            context.getString(R.string.workspace_archive_extract_subfolder, subFolderName),
            context.getString(R.string.workspace_archive_extract_to),
            "Mở chỉnh sửa văn bản"
        };

        new AlertDialog.Builder(context)
            .setTitle(archiveFile.getName())
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    showArchiveContentsDialog(context, archiveFile, currentDir, onExtracted);
                } else if (which == 1) {
                    runExtract(context, archiveFile, currentDir, onExtracted);
                } else if (which == 2) {
                    runExtract(context, archiveFile, new File(currentDir, subFolderName), onExtracted);
                } else if (which == 3) {
                    WorkspaceMoveHelper.showPickFolderOnlyDialog(context, currentDir,
                        "Giải nén \"" + archiveFile.getName() + "\" vào:",
                        "Giải nén vào đây",
                        destDir -> runExtract(context, archiveFile, destDir, onExtracted));
                } else if (which == 4) {
                    onOpenAsText.run();
                }
            })
            .show();
    }

    public static void showArchiveContentsDialog(@NonNull Context context,
                                                 @NonNull File archiveFile,
                                                 @NonNull File currentDir,
                                                 @NonNull Runnable onExtracted) {
        EXECUTOR.execute(() -> {
            try {
                List<WorkspaceArchiveHelper.ArchiveEntryInfo> entries =
                    WorkspaceArchiveHelper.listArchiveEntries(archiveFile);
                String[] lines = new String[entries.size()];
                long totalSize = 0L;
                for (int i = 0; i < entries.size(); i++) {
                    WorkspaceArchiveHelper.ArchiveEntryInfo e = entries.get(i);
                    totalSize += e.size;
                    lines[i] = (e.isDirectory ? "[Thư mục] " : "") + e.path
                        + (e.isDirectory ? "" : " (" + formatSize(e.size) + ")");
                }
                final long finalTotal = totalSize;
                MAIN.post(() -> new AlertDialog.Builder(context)
                    .setTitle(archiveFile.getName() + " • " + entries.size() + " mục (" + formatSize(finalTotal) + ")")
                    .setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, lines), null)
                    .setPositiveButton(R.string.workspace_archive_extract_here,
                        (d, w) -> runExtract(context, archiveFile, currentDir, onExtracted))
                    .setNeutralButton(context.getString(R.string.workspace_archive_extract_subfolder,
                            WorkspaceArchiveHelper.stripArchiveExtension(archiveFile.getName())),
                        (d, w) -> runExtract(context, archiveFile,
                            new File(currentDir, WorkspaceArchiveHelper.stripArchiveExtension(archiveFile.getName())),
                            onExtracted))
                    .setNegativeButton("Đóng", null)
                    .show());
            } catch (IOException e) {
                MAIN.post(() -> Toast.makeText(context,
                    "Không đọc được file nén: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void runExtract(@NonNull Context context,
                                  @NonNull File archiveFile,
                                  @NonNull File destDir,
                                  @NonNull Runnable onExtracted) {
        Toast.makeText(context, "Đang giải nén " + archiveFile.getName() + "…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                int count = WorkspaceArchiveHelper.extract(archiveFile, destDir);
                MAIN.post(() -> {
                    Toast.makeText(context,
                        "Đã giải nén " + count + " tệp vào " + destDir.getName(),
                        Toast.LENGTH_LONG).show();
                    onExtracted.run();
                });
            } catch (IOException e) {
                MAIN.post(() -> Toast.makeText(context,
                    "Lỗi giải nén: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void runExtractBatch(@NonNull Context context,
                                       @NonNull List<File> archives,
                                       @NonNull File parentDir,
                                       @NonNull Runnable onCompleted) {
        List<File> validArchives = new ArrayList<>();
        for (File archive : archives) {
            if (archive != null && archive.isFile() && WorkspaceArchiveHelper.isArchiveFile(archive)) {
                validArchives.add(archive);
            }
        }
        if (validArchives.isEmpty()) return;

        Toast.makeText(context, "Đang giải nén " + validArchives.size() + " kho lưu trữ…",
            Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            int succeeded = 0;
            int failed = 0;
            String firstError = null;
            for (File archive : validArchives) {
                String folderName = WorkspaceArchiveHelper.stripArchiveExtension(archive.getName());
                File targetDir = WorkspaceArchiveHelper.resolveUniqueTarget(parentDir, folderName, true);
                try {
                    WorkspaceArchiveHelper.extract(archive, targetDir);
                    succeeded++;
                } catch (IOException e) {
                    failed++;
                    if (firstError == null) firstError = archive.getName() + ": " + e.getMessage();
                }
            }
            final int successCount = succeeded;
            final int failedCount = failed;
            final String error = firstError;
            MAIN.post(() -> {
                String message = "Đã giải nén " + successCount + "/" + validArchives.size() + " tệp nén";
                if (failedCount > 0) message += " • lỗi " + failedCount + (error != null ? "\n" + error : "");
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                onCompleted.run();
            });
        });
    }

    public static void showDetailsDialog(@NonNull Context context, @NonNull File item) {
        EXECUTOR.execute(() -> {
            WorkspaceArchiveHelper.DirStats stats = WorkspaceArchiveHelper.computeStats(item);
            SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
            String perms = (item.canRead() ? "r" : "-") + (item.canWrite() ? "w" : "-") + (item.canExecute() ? "x" : "-");
            StringBuilder msg = new StringBuilder();
            msg.append("Tên: ").append(item.getName()).append("\n\n");
            msg.append("Đường dẫn:\n").append(item.getAbsolutePath()).append("\n\n");
            msg.append("Loại: ").append(item.isDirectory() ? "Thư mục" : "Tệp tin").append("\n");
            if (item.isDirectory()) {
                msg.append("Chứa: ").append(stats.folders).append(" thư mục con, ")
                    .append(stats.files).append(" tệp\n");
                msg.append("Tổng dung lượng: ").append(formatSize(stats.totalBytes))
                    .append(" (").append(stats.totalBytes).append(" byte)\n");
            } else {
                msg.append("Kích thước: ").append(formatSize(item.length()))
                    .append(" (").append(item.length()).append(" byte)\n");
            }
            msg.append("Quyền: ").append(perms).append("\n");
            msg.append("Sửa lần cuối: ").append(df.format(new Date(item.lastModified())));

            MAIN.post(() -> new AlertDialog.Builder(context)
                .setTitle(R.string.workspace_item_details)
                .setMessage(msg.toString())
                .setPositiveButton("Sao chép đường dẫn", (d, w) -> copyPathToClipboard(context, item))
                .setNegativeButton("Đóng", null)
                .show());
        });
    }

    public static void copyPathToClipboard(@NonNull Context context, @NonNull File item) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("path", item.getAbsolutePath()));
            Toast.makeText(context, "Đã sao chép đường dẫn", Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private static String stripExt(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @NonNull
    private static String formatSize(long bytes) {
        return WorkspaceArchiveHelper.formatSize(bytes);
    }
}

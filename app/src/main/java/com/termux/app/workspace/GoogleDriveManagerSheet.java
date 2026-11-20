package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.termux.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Drive cloud workspace sheet:
 * OAuth connect/disconnect, browse folders, backup workspace as .zip,
 * upload files/folders recursively, download files/folders, rename, and delete.
 */
public final class GoogleDriveManagerSheet {

    private static final class FolderCrumb {
        final String id;
        final String name;

        FolderCrumb(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private interface DriveRowListener {
        void onClick(@NonNull GoogleDriveApiClient.DriveItem item);
        void onCheckChanged(@NonNull GoogleDriveApiClient.DriveItem item);
        void onMore(@NonNull View anchor, @NonNull GoogleDriveApiClient.DriveItem item);
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final Deque<FolderCrumb> mFolderStack = new ArrayDeque<>();
    private final List<GoogleDriveApiClient.DriveItem> mEntries = new ArrayList<>();
    private final Set<String> mSelectedIds = new HashSet<>();

    private String mAccessToken;
    private String mSearchQuery = "";
    private boolean mBusy = false;
    private TextView mAccountStatusView;
    private TextView mEmptyTextView;
    private Runnable mUpdateHeaderUi;
    private Runnable mConnectFlow;

    public void show(@NonNull Activity activity, @NonNull File localDir, @Nullable Runnable onLocalChanged) {
        mFolderStack.clear();
        mFolderStack.push(new FolderCrumb("root", "My Drive"));

        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.Theme_WorkspaceBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_google_drive, null);
        dialog.setContentView(root);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        TextView accountStatus = root.findViewById(R.id.drive_account_status);
        mAccountStatusView = accountStatus;
        TextView pathBar = root.findViewById(R.id.drive_path_bar);
        ImageButton upBtn = root.findViewById(R.id.btn_drive_up);
        ImageButton refreshBtn = root.findViewById(R.id.btn_drive_refresh);
        MaterialButton connectBtn = root.findViewById(R.id.btn_drive_connect);
        MaterialButton backupZipBtn = root.findViewById(R.id.btn_drive_backup_zip);
        MaterialButton uploadLocalBtn = root.findViewById(R.id.btn_drive_upload_local);
        MaterialButton newFolderBtn = root.findViewById(R.id.btn_drive_new_folder);
        EditText searchInput = root.findViewById(R.id.drive_search_input);
        View progressContainer = root.findViewById(R.id.drive_progress_bar_container);
        TextView progressText = root.findViewById(R.id.drive_progress_text);
        RecyclerView recyclerView = root.findViewById(R.id.drive_file_list);
        View emptyView = root.findViewById(R.id.drive_empty_view);
        TextView emptyText = root.findViewById(R.id.drive_empty_text);
        mEmptyTextView = emptyText;
        View batchBar = root.findViewById(R.id.drive_batch_bar);
        MaterialButton batchClearBtn = root.findViewById(R.id.btn_drive_batch_clear);
        MaterialButton batchDeleteBtn = root.findViewById(R.id.btn_drive_batch_delete);
        MaterialButton batchDownloadBtn = root.findViewById(R.id.btn_drive_batch_download);

        DriveAdapter adapter = new DriveAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);

        Runnable updateHeaderUi = () -> {
            StringBuilder breadcrumb = new StringBuilder("Drive: /");
            List<FolderCrumb> ordered = new ArrayList<>(mFolderStack);
            Collections.reverse(ordered);
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0) breadcrumb.append("/");
                breadcrumb.append(ordered.get(i).name);
            }
            breadcrumb.append("  •  Máy: ").append(localDir.getName());
            pathBar.setText(breadcrumb.toString());

            boolean connected = mAccessToken != null && !mAccessToken.isEmpty();
            connectBtn.setText(connected ? R.string.drive_disconnect_btn : R.string.drive_connect_btn);
            connectBtn.setTextColor(Color.parseColor(connected ? "#F87171" : "#38BDF8"));

            int selCount = mSelectedIds.size();
            if (selCount > 0) {
                batchBar.setVisibility(View.VISIBLE);
                batchDeleteBtn.setText(activity.getString(R.string.drive_delete_selected, selCount));
                batchDownloadBtn.setText(activity.getString(R.string.drive_download_selected, selCount));
            } else {
                batchBar.setVisibility(View.GONE);
            }
        };

        Runnable showBusy = () -> {
            progressContainer.setVisibility(mBusy ? View.VISIBLE : View.GONE);
        };

        mUpdateHeaderUi = updateHeaderUi;
        final Runnable[] refreshDriveRef = new Runnable[1];
        final Runnable[] connectFlowRef = new Runnable[1];

        refreshDriveRef[0] = () -> {
            if (mAccessToken == null || mAccessToken.isEmpty()) {
                mEntries.clear();
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(View.VISIBLE);
                emptyText.setText("Chạm \"Kết nối\" để đăng nhập Google Drive.");
                updateHeaderUi.run();
                return;
            }
            mBusy = true;
            progressText.setText("Đang tải danh sách Google Drive…");
            showBusy.run();

            final String folderId = mFolderStack.peek() != null ? mFolderStack.peek().id : "root";
            final String query = mSearchQuery;
            mExecutor.execute(() -> {
                try {
                    GoogleDriveApiClient.DriveAccountInfo info = null;
                    try {
                        info = GoogleDriveApiClient.fetchAccountInfo(mAccessToken);
                    } catch (IOException aboutErr) {
                        if (aboutErr instanceof GoogleDriveApiClient.DriveApiException) {
                            GoogleDriveApiClient.DriveApiException dae =
                                (GoogleDriveApiClient.DriveApiException) aboutErr;
                            if (dae.httpCode == 401 || dae.isAccessNotConfigured() || dae.isInsufficientPermissions()) {
                                throw aboutErr;
                            }
                        }
                    }
                    List<GoogleDriveApiClient.DriveItem> items = GoogleDriveApiClient.listFiles(mAccessToken, folderId, query);
                    final GoogleDriveApiClient.DriveAccountInfo finalInfo = info;
                    mMainHandler.post(() -> {
                        mBusy = false;
                        showBusy.run();
                        if (finalInfo != null) {
                            String quotaStr = finalInfo.limitBytes > 0
                                ? " (" + formatBytes(finalInfo.usedBytes) + " / " + formatBytes(finalInfo.limitBytes) + ")"
                                : "";
                            accountStatus.setText((finalInfo.email.isEmpty() ? finalInfo.displayName : finalInfo.email) + quotaStr);
                        } else {
                            accountStatus.setText("Đã kết nối Google Drive");
                        }
                        mEntries.clear();
                        mEntries.addAll(items);
                        mSelectedIds.clear();
                        adapter.notifyDataSetChanged();
                        emptyView.setVisibility(mEntries.isEmpty() ? View.VISIBLE : View.GONE);
                        emptyText.setText(R.string.drive_empty_prompt);
                        updateHeaderUi.run();
                    });
                } catch (IOException e) {
                    mMainHandler.post(() -> {
                        mBusy = false;
                        showBusy.run();
                        if (mEntries.isEmpty()) {
                            emptyView.setVisibility(View.VISIBLE);
                        }
                        handleDriveError(activity, e, true);
                    });
                }
            });
        };

        connectFlowRef[0] = () -> GoogleDriveAuthHelper.getInstance().authorize(activity, false,
            new GoogleDriveAuthHelper.AuthCallback() {
                @Override
                public void onAuthorized(@NonNull String accessToken) {
                    mAccessToken = accessToken;
                    updateHeaderUi.run();
                    refreshDriveRef[0].run();
                }

                @Override
                public void onAuthFailed(@NonNull String errorMessage) {
                    accountStatus.setText(R.string.drive_not_connected);
                    emptyView.setVisibility(View.VISIBLE);
                    emptyText.setText(errorMessage);
                    Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show();
                    updateHeaderUi.run();
                }
            });
        Runnable connectFlow = connectFlowRef[0];
        mConnectFlow = connectFlow;

        adapter.setListener(new DriveRowListener() {
            @Override
            public void onClick(@NonNull GoogleDriveApiClient.DriveItem item) {
                if (item.isFolder()) {
                    mFolderStack.push(new FolderCrumb(item.id, item.name));
                    mSearchQuery = "";
                    searchInput.setText("");
                    updateHeaderUi.run();
                    refreshDriveRef[0].run();
                } else {
                    confirmDownloadItems(activity, Collections.singletonList(item), localDir,
                        progressContainer, progressText, onLocalChanged);
                }
            }

            @Override
            public void onCheckChanged(@NonNull GoogleDriveApiClient.DriveItem item) {
                if (mSelectedIds.contains(item.id)) {
                    mSelectedIds.remove(item.id);
                } else {
                    mSelectedIds.add(item.id);
                }
                updateHeaderUi.run();
            }

            @Override
            public void onMore(@NonNull View anchor, @NonNull GoogleDriveApiClient.DriveItem item) {
                PopupMenu popup = new PopupMenu(activity, anchor);
                if (item.isFolder()) {
                    popup.getMenu().add(0, 1, 0, "Mở thư mục");
                    popup.getMenu().add(0, 2, 1, "Tải cả thư mục về máy");
                } else {
                    popup.getMenu().add(0, 2, 0, "Tải về thư mục hiện tại");
                }
                popup.getMenu().add(0, 3, 2, "Đổi tên trên Drive");
                popup.getMenu().add(0, 4, 3, "Xóa trên Drive");

                popup.setOnMenuItemClickListener(mi -> {
                    int id = mi.getItemId();
                    if (id == 1 && item.isFolder()) {
                        mFolderStack.push(new FolderCrumb(item.id, item.name));
                        updateHeaderUi.run();
                        refreshDriveRef[0].run();
                        return true;
                    } else if (id == 2) {
                        confirmDownloadItems(activity, Collections.singletonList(item), localDir,
                            progressContainer, progressText, onLocalChanged);
                        return true;
                    } else if (id == 3) {
                        promptRenameDriveItem(activity, item, progressContainer, progressText, refreshDriveRef[0]);
                        return true;
                    } else if (id == 4) {
                        confirmDeleteDriveItems(activity, Collections.singletonList(item),
                            progressContainer, progressText, refreshDriveRef[0]);
                        return true;
                    }
                    return false;
                });
                popup.show();
            }
        });

        connectBtn.setOnClickListener(v -> {
            if (mAccessToken != null && !mAccessToken.isEmpty()) {
                GoogleDriveAuthHelper.getInstance().invalidateSession(activity);
                mAccessToken = null;
                accountStatus.setText(R.string.drive_not_connected);
                mEntries.clear();
                mSelectedIds.clear();
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(View.VISIBLE);
                emptyText.setText("Đã ngắt kết nối Google Drive.");
                updateHeaderUi.run();
            } else {
                connectFlow.run();
            }
        });

        upBtn.setOnClickListener(v -> {
            if (mFolderStack.size() > 1) {
                mFolderStack.pop();
                updateHeaderUi.run();
                refreshDriveRef[0].run();
            }
        });

        refreshBtn.setOnClickListener(v -> refreshDriveRef[0].run());

        newFolderBtn.setOnClickListener(v -> {
            if (!ensureConnected(activity, connectFlow)) return;
            promptName(activity, "Tạo thư mục trên Drive", "termux_folder", name -> {
                String parentId = mFolderStack.peek() != null ? mFolderStack.peek().id : "root";
                mBusy = true;
                progressText.setText("Đang tạo thư mục: " + name);
                showBusy.run();
                mExecutor.execute(() -> {
                    try {
                        GoogleDriveApiClient.createFolder(mAccessToken, name, parentId);
                        mMainHandler.post(() -> {
                            mBusy = false;
                            showBusy.run();
                            refreshDriveRef[0].run();
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> {
                            mBusy = false;
                            showBusy.run();
                            handleDriveError(activity, e, false);
                        });
                    }
                });
            });
        });

        backupZipBtn.setOnClickListener(v -> {
            if (!ensureConnected(activity, connectFlow)) return;
            runWorkspaceBackupZip(activity, localDir, progressContainer, progressText, refreshDriveRef[0]);
        });

        uploadLocalBtn.setOnClickListener(v -> {
            if (!ensureConnected(activity, connectFlow)) return;
            String parentId = mFolderStack.peek() != null ? mFolderStack.peek().id : "root";
            showLocalUploadPicker(activity, localDir, parentId, progressContainer, progressText, refreshDriveRef[0]);
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mSearchQuery = s != null ? s.toString().trim() : "";
                if (mAccessToken != null && !mAccessToken.isEmpty()) {
                    refreshDriveRef[0].run();
                }
            }
        });

        batchClearBtn.setOnClickListener(v -> {
            mSelectedIds.clear();
            adapter.notifyDataSetChanged();
            updateHeaderUi.run();
        });

        batchDownloadBtn.setOnClickListener(v -> {
            List<GoogleDriveApiClient.DriveItem> chosen = collectSelectedItems();
            if (!chosen.isEmpty()) {
                confirmDownloadItems(activity, chosen, localDir, progressContainer, progressText, onLocalChanged);
            }
        });

        batchDeleteBtn.setOnClickListener(v -> {
            List<GoogleDriveApiClient.DriveItem> chosen = collectSelectedItems();
            if (!chosen.isEmpty()) {
                confirmDeleteDriveItems(activity, chosen, progressContainer, progressText, refreshDriveRef[0]);
            }
        });

        dialog.setOnDismissListener(d -> mExecutor.shutdownNow());

        updateHeaderUi.run();
        dialog.show();

        // Automatically attempt silent token reuse or trigger OAuth if cached
        String cached = GoogleDriveAuthHelper.getInstance().getCachedToken();
        if (cached != null) {
            mAccessToken = cached;
            updateHeaderUi.run();
            refreshDriveRef[0].run();
        } else {
            connectFlow.run();
        }
    }

    @NonNull
    private List<GoogleDriveApiClient.DriveItem> collectSelectedItems() {
        List<GoogleDriveApiClient.DriveItem> list = new ArrayList<>();
        for (GoogleDriveApiClient.DriveItem item : mEntries) {
            if (mSelectedIds.contains(item.id)) list.add(item);
        }
        return list;
    }

    private boolean ensureConnected(@NonNull Activity activity, @NonNull Runnable connectFlow) {
        if (mAccessToken != null && !mAccessToken.isEmpty()) return true;
        Toast.makeText(activity, "Đang kết nối Google Drive…", Toast.LENGTH_SHORT).show();
        connectFlow.run();
        return false;
    }

    private void runWorkspaceBackupZip(@NonNull Activity activity,
                                       @NonNull File localDir,
                                       @NonNull View progressContainer,
                                       @NonNull TextView progressText,
                                       @NonNull Runnable onDone) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String dirName = localDir.getName().isEmpty() ? "workspace" : localDir.getName();
        String defaultZipName = "backup_" + dirName + "_" + ts + ".zip";

        new AlertDialog.Builder(activity)
            .setTitle("Sao lưu thư mục lên Google Drive")
            .setMessage("Nén toàn bộ thư mục \"" + dirName + "\" thành \"" + defaultZipName
                + "\" và tải vào thư mục \"Termux Backups\" trên Google Drive?")
            .setPositiveButton("Sao lưu ngay", (d, w) -> {
                mBusy = true;
                progressContainer.setVisibility(View.VISIBLE);
                progressText.setText("Đang nén " + dirName + "…");

                mExecutor.execute(() -> {
                    File tempZip = new File(activity.getCacheDir(), defaultZipName);
                    try {
                        File[] children = localDir.listFiles();
                        List<File> sources = new ArrayList<>();
                        if (children != null) {
                            Collections.addAll(sources, children);
                        }
                        if (sources.isEmpty()) {
                            throw new IOException("Thư mục hiện tại đang trống, không có gì để sao lưu.");
                        }
                        WorkspaceArchiveHelper.compress(sources, tempZip, WorkspaceArchiveHelper.ArchiveFormat.ZIP);

                        mMainHandler.post(() -> progressText.setText("Đang tìm/tạo thư mục Termux Backups…"));
                        GoogleDriveApiClient.DriveItem backupFolder =
                            GoogleDriveApiClient.findOrCreateFolder(mAccessToken, "Termux Backups", "root");

                        GoogleDriveApiClient.uploadFile(mAccessToken, tempZip, backupFolder.id,
                            msg -> mMainHandler.post(() -> progressText.setText(msg)));

                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            Toast.makeText(activity,
                                "Đã sao lưu thành công vào Termux Backups/" + defaultZipName,
                                Toast.LENGTH_LONG).show();
                            onDone.run();
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            handleDriveError(activity, e, false);
                        });
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        tempZip.delete();
                    }
                });
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void showLocalUploadPicker(@NonNull Activity activity,
                                       @NonNull File localDir,
                                       @NonNull String targetDriveFolderId,
                                       @NonNull View progressContainer,
                                       @NonNull TextView progressText,
                                       @NonNull Runnable onDone) {
        File[] children = localDir.listFiles();
        if (children == null || children.length == 0) {
            Toast.makeText(activity, "Thư mục máy hiện tại đang trống.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> items = new ArrayList<>();
        Collections.addAll(items, children);
        Collections.sort(items, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        String[] labels = new String[items.size()];
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            File f = items.get(i);
            labels[i] = (f.isDirectory() ? "[Thư mục] " : "") + f.getName();
        }

        new AlertDialog.Builder(activity)
            .setTitle("Chọn tệp / thư mục để upload")
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Upload", (dialog, which) -> {
                List<File> selected = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    if (checked[i]) selected.add(items.get(i));
                }
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "Chưa chọn tệp hoặc thư mục nào.", Toast.LENGTH_SHORT).show();
                    return;
                }

                mBusy = true;
                progressContainer.setVisibility(View.VISIBLE);
                progressText.setText("Đang upload " + selected.size() + " mục…");

                mExecutor.execute(() -> {
                    int uploaded = 0;
                    try {
                        for (File f : selected) {
                            if (f.isDirectory()) {
                                uploaded += GoogleDriveApiClient.uploadFolderRecursively(
                                    mAccessToken, f, targetDriveFolderId,
                                    msg -> mMainHandler.post(() -> progressText.setText(msg)));
                            } else {
                                GoogleDriveApiClient.uploadFile(
                                    mAccessToken, f, targetDriveFolderId,
                                    msg -> mMainHandler.post(() -> progressText.setText(msg)));
                                uploaded++;
                            }
                        }
                        final int totalUploaded = uploaded;
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            Toast.makeText(activity, "Đã upload xong " + totalUploaded + " tệp lên Google Drive", Toast.LENGTH_LONG).show();
                            onDone.run();
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            handleDriveError(activity, e, false);
                        });
                    }
                });
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void confirmDownloadItems(@NonNull Activity activity,
                                      @NonNull List<GoogleDriveApiClient.DriveItem> items,
                                      @NonNull File localDir,
                                      @NonNull View progressContainer,
                                      @NonNull TextView progressText,
                                      @Nullable Runnable onLocalChanged) {
        String title = items.size() == 1
            ? "Tải \"" + items.get(0).name + "\" về máy?"
            : "Tải " + items.size() + " mục về máy?";

        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Thư mục lưu trên máy:\n" + localDir.getAbsolutePath())
            .setPositiveButton("Tải xuống", (d, w) -> {
                mBusy = true;
                progressContainer.setVisibility(View.VISIBLE);
                progressText.setText("Đang chuẩn bị tải xuống…");

                mExecutor.execute(() -> {
                    int count = 0;
                    try {
                        for (GoogleDriveApiClient.DriveItem item : items) {
                            if (item.isFolder()) {
                                count += GoogleDriveApiClient.downloadFolderRecursively(
                                    mAccessToken, item, localDir,
                                    msg -> mMainHandler.post(() -> progressText.setText(msg)));
                            } else {
                                GoogleDriveApiClient.downloadFile(
                                    mAccessToken, item, localDir,
                                    msg -> mMainHandler.post(() -> progressText.setText(msg)));
                                count++;
                            }
                        }
                        final int downloaded = count;
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            Toast.makeText(activity, "Đã tải xuống " + downloaded + " tệp vào " + localDir.getName(), Toast.LENGTH_LONG).show();
                            if (onLocalChanged != null) onLocalChanged.run();
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            handleDriveError(activity, e, false);
                        });
                    }
                });
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void promptRenameDriveItem(@NonNull Activity activity,
                                       @NonNull GoogleDriveApiClient.DriveItem item,
                                       @NonNull View progressContainer,
                                       @NonNull TextView progressText,
                                       @NonNull Runnable onDone) {
        promptName(activity, "Đổi tên trên Drive", item.name, newName -> {
            mBusy = true;
            progressContainer.setVisibility(View.VISIBLE);
            progressText.setText("Đang đổi tên…");
            mExecutor.execute(() -> {
                try {
                    GoogleDriveApiClient.renameFile(mAccessToken, item.id, newName);
                    mMainHandler.post(() -> {
                        mBusy = false;
                        progressContainer.setVisibility(View.GONE);
                        onDone.run();
                    });
                } catch (IOException e) {
                    mMainHandler.post(() -> {
                        mBusy = false;
                        progressContainer.setVisibility(View.GONE);
                        handleDriveError(activity, e, false);
                    });
                }
            });
        });
    }

    private void confirmDeleteDriveItems(@NonNull Activity activity,
                                         @NonNull List<GoogleDriveApiClient.DriveItem> items,
                                         @NonNull View progressContainer,
                                         @NonNull TextView progressText,
                                         @NonNull Runnable onDone) {
        String msg = items.size() == 1
            ? "Xóa \"" + items.get(0).name + "\" khỏi Google Drive?"
            : "Xóa vĩnh viễn " + items.size() + " mục đã chọn khỏi Google Drive?";

        new AlertDialog.Builder(activity)
            .setTitle("Xác nhận xóa trên Drive")
            .setMessage(msg)
            .setPositiveButton("Xóa", (d, w) -> {
                mBusy = true;
                progressContainer.setVisibility(View.VISIBLE);
                progressText.setText("Đang xóa trên Drive…");
                mExecutor.execute(() -> {
                    try {
                        for (GoogleDriveApiClient.DriveItem item : items) {
                            GoogleDriveApiClient.deleteFile(mAccessToken, item.id);
                        }
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            Toast.makeText(activity, "Đã xóa " + items.size() + " mục trên Google Drive", Toast.LENGTH_SHORT).show();
                            onDone.run();
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> {
                            mBusy = false;
                            progressContainer.setVisibility(View.GONE);
                            handleDriveError(activity, e, false);
                        });
                    }
                });
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private interface NameSubmit {
        void onSubmit(@NonNull String name);
    }

    private static void promptName(@NonNull Activity activity, @NonNull String title,
                                   @NonNull String initial, @NonNull NameSubmit cb) {
        EditText input = new EditText(activity);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Xác nhận", (d, w) -> {
                String text = input.getText() != null ? input.getText().toString().trim() : "";
                if (!text.isEmpty()) cb.onSubmit(text);
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private final class DriveAdapter extends RecyclerView.Adapter<DriveAdapter.VH> {
        private DriveRowListener mListener;

        void setListener(DriveRowListener listener) {
            mListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_drive_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GoogleDriveApiClient.DriveItem item = mEntries.get(position);
            holder.name.setText(item.name);

            if (item.isFolder()) {
                holder.icon.setImageResource(R.drawable.ic_workspace_folder);
                holder.icon.setColorFilter(Color.parseColor("#F59E0B"));
                holder.meta.setText("Thư mục Drive");
            } else {
                holder.icon.setImageResource(R.drawable.ic_workspace_file);
                holder.icon.setColorFilter(Color.parseColor("#38BDF8"));
                String dateStr = item.modifiedTime.length() >= 10 ? item.modifiedTime.substring(0, 10) : "";
                String sizeStr = item.size > 0 ? formatBytes(item.size) : "Google Doc";
                holder.meta.setText(sizeStr + (dateStr.isEmpty() ? "" : " • " + dateStr));
            }

            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(mSelectedIds.contains(item.id));
            holder.checkbox.setOnCheckedChangeListener((b, c) -> {
                if (mListener != null) mListener.onCheckChanged(item);
            });

            holder.itemView.setOnClickListener(v -> {
                if (mListener != null) mListener.onClick(item);
            });
            holder.moreBtn.setOnClickListener(v -> {
                if (mListener != null) mListener.onMore(v, item);
            });
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView meta;
            final CheckBox checkbox;
            final ImageButton moreBtn;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.drive_item_icon);
                name = itemView.findViewById(R.id.drive_item_name);
                meta = itemView.findViewById(R.id.drive_item_meta);
                checkbox = itemView.findViewById(R.id.drive_item_checkbox);
                moreBtn = itemView.findViewById(R.id.drive_item_more);
            }
        }
    }

    private void handleDriveError(@NonNull Activity activity,
                                  @NonNull IOException error,
                                  boolean updateEmptyViewText) {
        String title = "Lỗi Google Drive API";
        String body = error.getMessage() != null ? error.getMessage() : "Lỗi không xác định";
        String helpUrl = null;
        boolean needsReauth = false;
        boolean canTryDriveFileScope = false;

        if (error instanceof GoogleDriveApiClient.DriveApiException) {
            GoogleDriveApiClient.DriveApiException dae = (GoogleDriveApiClient.DriveApiException) error;
            title = "Google Drive API (" + dae.httpCode + ")";
            body = dae.getVietnameseGuidance();
            helpUrl = dae.helpUrl;
            if (dae.httpCode == 401 || dae.isInsufficientPermissions()) {
                needsReauth = true;
                GoogleDriveAuthHelper.getInstance().invalidateSession(activity);
                mAccessToken = null;
                if (mAccountStatusView != null) {
                    mAccountStatusView.setText(R.string.drive_not_connected);
                }
            }
            if (dae.httpCode == 403
                && GoogleDriveAuthHelper.DRIVE_SCOPE.equals(GoogleDriveAuthHelper.getInstance().getActiveScope())
                && !dae.isAccessNotConfigured()) {
                canTryDriveFileScope = true;
            }
        }

        if (updateEmptyViewText && mEmptyTextView != null) {
            mEmptyTextView.setText(body);
        }
        if (mUpdateHeaderUi != null) {
            mUpdateHeaderUi.run();
        }

        if (activity.isFinishing() || activity.isDestroyed()) return;

        final String copyText = title + "\n" + body + (helpUrl != null ? "\n" + helpUrl : "");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(body)
            .setNegativeButton("Đóng", null);

        builder.setNeutralButton("Sao chép lỗi", (d, w) -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("drive_error", copyText));
                Toast.makeText(activity, "Đã sao chép chi tiết lỗi", Toast.LENGTH_SHORT).show();
            }
        });

        if (helpUrl != null && !helpUrl.isEmpty()) {
            final String targetUrl = helpUrl;
            builder.setPositiveButton("Mở Google Console", (d, w) -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)));
                } catch (Exception ex) {
                    Toast.makeText(activity, targetUrl, Toast.LENGTH_LONG).show();
                }
            });
        } else if (needsReauth && mConnectFlow != null) {
            builder.setPositiveButton("Cấp lại quyền", (d, w) -> mConnectFlow.run());
        } else if (canTryDriveFileScope && mConnectFlow != null) {
            builder.setPositiveButton("Thử quyền drive.file", (d, w) -> {
                GoogleDriveAuthHelper.getInstance().setUseDriveFileScopeOnly(true);
                GoogleDriveAuthHelper.getInstance().invalidateSession(activity);
                mAccessToken = null;
                mConnectFlow.run();
            });
        }

        builder.show();
    }

    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Extra-key Workspace Menu displaying the files and folders in the current directory,
 * supporting remembered folder persistence, ZArchiver/RAR file & archive operations,
 * Pydroid-style multi-file batch editing, pasted tree diagrams, and Google Drive backup/sync.
 */
public final class WorkspaceMenuBottomSheet {

    public interface HostActions {
        void onOpenFilesInBatchEditor(@NonNull List<File> files, @NonNull File currentDir);
        void onSendCdCommandToTerminal(@NonNull File targetDir);
    }

    private interface FileItemListener {
        void onItemClick(@NonNull File item);
        void onCheckboxToggle(@NonNull File item);
        void onMoreClick(@NonNull View anchor, @NonNull File item);
    }

    private static final int MENU_OPEN = 1;
    private static final int MENU_TREE = 2;
    private static final int MENU_CD = 3;
    private static final int MENU_RENAME = 4;
    private static final int MENU_DELETE = 5;
    private static final int MENU_MOVE_TO = 6;
    private static final int MENU_COPY = 7;
    private static final int MENU_CUT = 8;
    private static final int MENU_COPY_TO = 9;
    private static final int MENU_ARCHIVE_VIEW = 10;
    private static final int MENU_ARCHIVE_EXTRACT_HERE = 11;
    private static final int MENU_ARCHIVE_EXTRACT_SUB = 12;
    private static final int MENU_DUPLICATE = 13;
    private static final int MENU_COMPRESS = 14;
    private static final int MENU_COPY_PATH = 15;
    private static final int MENU_DETAILS = 16;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private File mCurrentDir;
    private final List<File> mAllEntries = new ArrayList<>();
    private final List<File> mFilteredEntries = new ArrayList<>();
    private final Set<String> mSelectedFilePaths = new HashSet<>();
    private boolean mBatchSelectMode = false;
    private String mFilterQuery = "";

    public void show(@NonNull Activity activity, @NonNull File initialDir, @NonNull HostActions hostActions) {
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        mCurrentDir = initialDir.isDirectory() ? initialDir : TermuxConstants.TERMUX_HOME_DIR;
        if (!mCurrentDir.exists()) {
            mCurrentDir = activity.getFilesDir();
        }
        store.setCurrentDirectory(activity, mCurrentDir);

        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.Theme_WorkspaceBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_workspace_menu, null);
        dialog.setContentView(root);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        TextView pathText = root.findViewById(R.id.workspace_current_path);
        ImageButton upBtn = root.findViewById(R.id.btn_nav_up);
        ImageButton homeBtn = root.findViewById(R.id.btn_nav_home);
        ImageButton cdBtn = root.findViewById(R.id.btn_nav_cd_terminal);
        ImageButton refreshBtn = root.findViewById(R.id.btn_nav_refresh);

        MaterialButton treeBtn = root.findViewById(R.id.btn_open_tree_creator);
        MaterialButton batchToggleBtn = root.findViewById(R.id.btn_batch_select_toggle);
        MaterialButton newFileBtn = root.findViewById(R.id.btn_new_file);
        MaterialButton driveBtn = root.findViewById(R.id.btn_open_google_drive);
        MaterialButton newFolderBtn = root.findViewById(R.id.btn_new_folder);

        EditText searchInput = root.findViewById(R.id.workspace_search_input);
        RecyclerView recyclerView = root.findViewById(R.id.workspace_file_list);
        View emptyView = root.findViewById(R.id.workspace_empty_view);

        LinearLayout clipboardBar = root.findViewById(R.id.workspace_clipboard_bar);
        TextView clipboardText = root.findViewById(R.id.workspace_clipboard_text);
        MaterialButton clipboardClearBtn = root.findViewById(R.id.btn_clipboard_clear);
        MaterialButton clipboardPasteBtn = root.findViewById(R.id.btn_clipboard_paste);

        LinearLayout batchBar = root.findViewById(R.id.workspace_batch_bar);
        MaterialButton batchClearBtn = root.findViewById(R.id.btn_batch_clear);
        MaterialButton copyBatchBtn = root.findViewById(R.id.btn_copy_selected_batch);
        MaterialButton moveBatchBtn = root.findViewById(R.id.btn_move_selected_batch);
        MaterialButton compressBatchBtn = root.findViewById(R.id.btn_compress_selected_batch);
        MaterialButton openBatchBtn = root.findViewById(R.id.btn_open_selected_batch);

        FileAdapter adapter = new FileAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);

        Runnable updateClipboardBar = () -> {
            WorkspaceSessionStore.ClipboardMode mode = store.getClipboardMode();
            List<File> clipItems = store.getClipboardItems();
            if (mode == WorkspaceSessionStore.ClipboardMode.NONE || clipItems.isEmpty()) {
                clipboardBar.setVisibility(View.GONE);
            } else {
                clipboardBar.setVisibility(View.VISIBLE);
                int resId = mode == WorkspaceSessionStore.ClipboardMode.COPY
                    ? R.string.workspace_clipboard_copy_info
                    : R.string.workspace_clipboard_cut_info;
                clipboardText.setText(activity.getString(resId, clipItems.size()));
            }
        };

        Runnable updateBatchBar = () -> {
            int totalCount = mSelectedFilePaths.size();
            int editableFileCount = 0;
            for (String path : mSelectedFilePaths) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    editableFileCount++;
                }
            }
            if (totalCount > 0 || mBatchSelectMode) {
                batchBar.setVisibility(View.VISIBLE);
                boolean hasSelection = totalCount > 0;
                copyBatchBtn.setEnabled(hasSelection);
                moveBatchBtn.setEnabled(hasSelection);
                compressBatchBtn.setEnabled(hasSelection);
                if (hasSelection) {
                    copyBatchBtn.setText(activity.getString(R.string.workspace_menu_action_copy, totalCount));
                    moveBatchBtn.setText(activity.getString(R.string.workspace_menu_action_move, totalCount));
                    compressBatchBtn.setText(activity.getString(R.string.workspace_menu_action_compress, totalCount));
                } else {
                    copyBatchBtn.setText(R.string.workspace_item_copy);
                    moveBatchBtn.setText(R.string.workspace_menu_move_to);
                    compressBatchBtn.setText(R.string.workspace_item_compress);
                }
                if (editableFileCount > 0) {
                    openBatchBtn.setEnabled(true);
                    openBatchBtn.setText(activity.getString(R.string.workspace_menu_action_open_batch, editableFileCount));
                } else {
                    openBatchBtn.setEnabled(false);
                    openBatchBtn.setText(R.string.workspace_menu_action_open_selected_none);
                }
            } else {
                batchBar.setVisibility(View.GONE);
            }
            batchToggleBtn.setTextColor(Color.parseColor(mBatchSelectMode ? "#10B981" : "#38BDF8"));
        };

        Runnable reloadDirectory = () -> {
            pathText.setText(mCurrentDir.getAbsolutePath());
            store.setCurrentDirectory(activity, mCurrentDir);
            loadDirectoryEntries();
            applyFilter();
            adapter.notifyDataSetChanged();
            emptyView.setVisibility(mFilteredEntries.isEmpty() ? View.VISIBLE : View.GONE);
            updateBatchBar.run();
            updateClipboardBar.run();
        };

        adapter.setListener(new FileItemListener() {
            @Override
            public void onItemClick(@NonNull File item) {
                if (item.isDirectory()) {
                    mCurrentDir = item;
                    mFilterQuery = "";
                    searchInput.setText("");
                    reloadDirectory.run();
                } else if (mBatchSelectMode) {
                    toggleSelection(item);
                    adapter.notifyDataSetChanged();
                    updateBatchBar.run();
                } else if (WorkspaceArchiveHelper.isArchiveFile(item)) {
                    WorkspaceArchiveDialogs.showArchiveQuickMenu(activity, item, mCurrentDir, reloadDirectory, () -> {
                        dialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), mCurrentDir);
                    });
                } else {
                    dialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), mCurrentDir);
                }
            }

            @Override
            public void onCheckboxToggle(@NonNull File item) {
                toggleSelection(item);
                updateBatchBar.run();
            }

            @Override
            public void onMoreClick(@NonNull View anchor, @NonNull File item) {
                showItemPopupMenu(activity, anchor, item, dialog, hostActions, reloadDirectory, updateClipboardBar);
            }
        });

        upBtn.setOnClickListener(v -> {
            File parent = mCurrentDir.getParentFile();
            if (parent != null && parent.canRead()) {
                mCurrentDir = parent;
                reloadDirectory.run();
            }
        });

        homeBtn.setOnClickListener(v -> {
            mCurrentDir = TermuxConstants.TERMUX_HOME_DIR;
            reloadDirectory.run();
        });

        cdBtn.setOnClickListener(v -> {
            hostActions.onSendCdCommandToTerminal(mCurrentDir);
            Toast.makeText(activity, activity.getString(R.string.workspace_menu_cd_synced, mCurrentDir.getAbsolutePath()), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        refreshBtn.setOnClickListener(v -> reloadDirectory.run());

        treeBtn.setOnClickListener(v ->
            new TreeCreatorBottomSheet().show(activity, mCurrentDir, (dir, createdFiles, openInEditor) -> {
                reloadDirectory.run();
                if (openInEditor && !createdFiles.isEmpty()) {
                    dialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(createdFiles, mCurrentDir);
                }
            }));

        driveBtn.setOnClickListener(v -> new GoogleDriveManagerSheet().show(activity, mCurrentDir, reloadDirectory));

        batchToggleBtn.setOnClickListener(v -> {
            mBatchSelectMode = !mBatchSelectMode;
            if (!mBatchSelectMode && mSelectedFilePaths.isEmpty()) {
                mSelectedFilePaths.clear();
            }
            updateBatchBar.run();
            adapter.notifyDataSetChanged();
        });

        newFileBtn.setOnClickListener(v -> promptCreateName(activity, "Tạo tệp mới", "example.py", name -> {
            File target = new File(mCurrentDir, name);
            try {
                if (target.exists() || target.createNewFile()) {
                    reloadDirectory.run();
                    dialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(target), mCurrentDir);
                }
            } catch (IOException e) {
                Toast.makeText(activity, "Lỗi tạo tệp: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));

        newFolderBtn.setOnClickListener(v -> promptCreateName(activity, "Tạo thư mục mới", "new_folder", name -> {
            File target = new File(mCurrentDir, name);
            if (!target.exists() && !target.mkdirs()) {
                Toast.makeText(activity, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show();
            } else {
                reloadDirectory.run();
            }
        }));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mFilterQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                applyFilter();
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(mFilteredEntries.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        clipboardClearBtn.setOnClickListener(v -> {
            store.clearClipboard();
            updateClipboardBar.run();
        });

        clipboardPasteBtn.setOnClickListener(v -> {
            WorkspaceSessionStore.ClipboardMode mode = store.getClipboardMode();
            List<File> items = store.getClipboardItems();
            if (mode == WorkspaceSessionStore.ClipboardMode.NONE || items.isEmpty()) {
                updateClipboardBar.run();
                return;
            }
            File destDir = mCurrentDir;
            Toast.makeText(activity, "Đang xử lý " + items.size() + " mục…", Toast.LENGTH_SHORT).show();
            mExecutor.execute(() -> {
                if (mode == WorkspaceSessionStore.ClipboardMode.COPY) {
                    WorkspaceArchiveHelper.CopyResult res = WorkspaceArchiveHelper.copyItems(items, destDir, true);
                    mMainHandler.post(() -> {
                        if (res.copiedCount > 0) {
                            store.clearClipboard();
                            Toast.makeText(activity, "Đã dán " + res.copiedCount + " mục", Toast.LENGTH_SHORT).show();
                            reloadDirectory.run();
                        } else {
                            Toast.makeText(activity, res.lastError != null ? res.lastError : "Không thể dán", Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    WorkspaceMoveHelper.MoveResult res = WorkspaceMoveHelper.moveItems(activity, items, destDir);
                    mMainHandler.post(() -> {
                        if (res.movedCount > 0) {
                            store.clearClipboard();
                            Toast.makeText(activity, activity.getString(R.string.workspace_move_success, res.movedCount, destDir.getName()), Toast.LENGTH_SHORT).show();
                            reloadDirectory.run();
                        } else {
                            Toast.makeText(activity, res.lastError != null ? res.lastError : "Không thể di chuyển", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });

        batchClearBtn.setOnClickListener(v -> {
            mSelectedFilePaths.clear();
            mBatchSelectMode = false;
            adapter.notifyDataSetChanged();
            updateBatchBar.run();
        });

        copyBatchBtn.setOnClickListener(v -> {
            List<File> selected = collectSelectedEntries();
            if (!selected.isEmpty()) {
                store.setClipboard(WorkspaceSessionStore.ClipboardMode.COPY, selected);
                mSelectedFilePaths.clear();
                mBatchSelectMode = false;
                adapter.notifyDataSetChanged();
                updateBatchBar.run();
                updateClipboardBar.run();
                Toast.makeText(activity,
                    "Đã chép " + selected.size() + " mục — mở thư mục đích và bấm Dán tại đây",
                    Toast.LENGTH_SHORT).show();
            }
        });

        moveBatchBtn.setOnClickListener(v -> {
            List<File> toMove = collectSelectedEntries();
            if (!toMove.isEmpty()) {
                WorkspaceMoveHelper.showDestinationPicker(activity, toMove, mCurrentDir, (targetDir, movedCount) -> {
                    mSelectedFilePaths.clear();
                    mBatchSelectMode = false;
                    reloadDirectory.run();
                });
            }
        });

        compressBatchBtn.setOnClickListener(v -> {
            List<File> toCompress = collectSelectedEntries();
            if (!toCompress.isEmpty()) {
                WorkspaceArchiveDialogs.showCompressDialog(activity, toCompress, mCurrentDir, () -> {
                    mSelectedFilePaths.clear();
                    mBatchSelectMode = false;
                    reloadDirectory.run();
                });
            }
        });

        openBatchBtn.setOnClickListener(v -> {
            List<File> toOpen = new ArrayList<>();
            for (String path : mSelectedFilePaths) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    toOpen.add(f);
                }
            }
            if (!toOpen.isEmpty()) {
                dialog.dismiss();
                hostActions.onOpenFilesInBatchEditor(toOpen, mCurrentDir);
            }
        });

        reloadDirectory.run();
        dialog.show();
    }

    @NonNull
    private List<File> collectSelectedEntries() {
        List<File> list = new ArrayList<>();
        for (String path : mSelectedFilePaths) {
            File f = new File(path);
            if (f.exists()) {
                list.add(f);
            }
        }
        return list;
    }

    private void toggleSelection(@NonNull File file) {
        String path = file.getAbsolutePath();
        if (mSelectedFilePaths.contains(path)) {
            mSelectedFilePaths.remove(path);
        } else {
            mSelectedFilePaths.add(path);
        }
    }

    private void loadDirectoryEntries() {
        mAllEntries.clear();
        File[] children = mCurrentDir.listFiles();
        if (children == null) return;
        Collections.addAll(mAllEntries, children);
        Collections.sort(mAllEntries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
    }

    private void applyFilter() {
        mFilteredEntries.clear();
        if (mFilterQuery.isEmpty()) {
            mFilteredEntries.addAll(mAllEntries);
        } else {
            for (File f : mAllEntries) {
                if (f.getName().toLowerCase(Locale.ROOT).contains(mFilterQuery)) {
                    mFilteredEntries.add(f);
                }
            }
        }
    }

    private void showItemPopupMenu(@NonNull Activity activity, @NonNull View anchor, @NonNull File item,
                                   @NonNull BottomSheetDialog parentDialog, @NonNull HostActions hostActions,
                                   @NonNull Runnable reloadDirectory, @NonNull Runnable updateClipboardBar) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        int order = 0;
        if (item.isDirectory()) {
            popup.getMenu().add(0, MENU_OPEN, order++, "Mở sửa tất cả tệp bên trong");
            popup.getMenu().add(0, MENU_TREE, order++, "Dán sơ đồ cây vào thư mục này");
            popup.getMenu().add(0, MENU_CD, order++, "cd vào thư mục trong Terminal");
        } else {
            if (WorkspaceArchiveHelper.isArchiveFile(item)) {
                popup.getMenu().add(0, MENU_ARCHIVE_VIEW, order++, R.string.workspace_archive_view);
                popup.getMenu().add(0, MENU_ARCHIVE_EXTRACT_HERE, order++, R.string.workspace_archive_extract_here);
                popup.getMenu().add(0, MENU_ARCHIVE_EXTRACT_SUB, order++,
                    activity.getString(R.string.workspace_archive_extract_subfolder,
                        WorkspaceArchiveHelper.stripArchiveExtension(item.getName())));
            }
            popup.getMenu().add(0, MENU_OPEN, order++, "Mở chỉnh sửa văn bản");
        }
        popup.getMenu().add(0, MENU_COPY, order++, R.string.workspace_item_copy);
        popup.getMenu().add(0, MENU_CUT, order++, R.string.workspace_item_cut);
        popup.getMenu().add(0, MENU_COPY_TO, order++, R.string.workspace_item_copy_to);
        popup.getMenu().add(0, MENU_MOVE_TO, order++, R.string.workspace_menu_move_to);
        popup.getMenu().add(0, MENU_DUPLICATE, order++, R.string.workspace_item_duplicate);
        popup.getMenu().add(0, MENU_COMPRESS, order++, R.string.workspace_item_compress);
        popup.getMenu().add(0, MENU_RENAME, order++, "Đổi tên");
        popup.getMenu().add(0, MENU_COPY_PATH, order++, R.string.workspace_item_copy_path);
        popup.getMenu().add(0, MENU_DETAILS, order++, R.string.workspace_item_details);
        popup.getMenu().add(0, MENU_DELETE, order, "Xóa");

        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == MENU_OPEN) {
                if (item.isDirectory()) {
                    List<File> files = collectEditableFiles(item, 25);
                    if (files.isEmpty()) {
                        Toast.makeText(activity, "Không có tệp nào trong thư mục này", Toast.LENGTH_SHORT).show();
                    } else {
                        parentDialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(files, item);
                    }
                } else {
                    parentDialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), mCurrentDir);
                }
                return true;
            } else if (id == MENU_TREE && item.isDirectory()) {
                new TreeCreatorBottomSheet().show(activity, item, (dir, createdFiles, openInEditor) -> {
                    reloadDirectory.run();
                    if (openInEditor && !createdFiles.isEmpty()) {
                        parentDialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(createdFiles, item);
                    }
                });
                return true;
            } else if (id == MENU_CD && item.isDirectory()) {
                hostActions.onSendCdCommandToTerminal(item);
                parentDialog.dismiss();
                return true;
            } else if (id == MENU_ARCHIVE_VIEW) {
                WorkspaceArchiveDialogs.showArchiveContentsDialog(activity, item, mCurrentDir, reloadDirectory);
                return true;
            } else if (id == MENU_ARCHIVE_EXTRACT_HERE) {
                WorkspaceArchiveDialogs.runExtract(activity, item, mCurrentDir, reloadDirectory);
                return true;
            } else if (id == MENU_ARCHIVE_EXTRACT_SUB) {
                File sub = new File(mCurrentDir, WorkspaceArchiveHelper.stripArchiveExtension(item.getName()));
                WorkspaceArchiveDialogs.runExtract(activity, item, sub, reloadDirectory);
                return true;
            } else if (id == MENU_COPY) {
                store.setClipboard(WorkspaceSessionStore.ClipboardMode.COPY, Collections.singletonList(item));
                updateClipboardBar.run();
                Toast.makeText(activity, "Đã chép \"" + item.getName() + "\" vào bộ nhớ tạm", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == MENU_CUT) {
                store.setClipboard(WorkspaceSessionStore.ClipboardMode.CUT, Collections.singletonList(item));
                updateClipboardBar.run();
                Toast.makeText(activity, "Đã cắt \"" + item.getName() + "\" — chọn thư mục và bấm Dán tại đây", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == MENU_COPY_TO) {
                WorkspaceMoveHelper.showCopyDestinationPicker(activity, Collections.singletonList(item), mCurrentDir,
                    (targetDir, copiedCount) -> reloadDirectory.run());
                return true;
            } else if (id == MENU_MOVE_TO) {
                WorkspaceMoveHelper.showDestinationPicker(activity, Collections.singletonList(item), mCurrentDir,
                    (targetDir, movedCount) -> {
                        mSelectedFilePaths.remove(item.getAbsolutePath());
                        reloadDirectory.run();
                    });
                return true;
            } else if (id == MENU_DUPLICATE) {
                mExecutor.execute(() -> {
                    try {
                        File dup = WorkspaceArchiveHelper.duplicateItem(item);
                        mMainHandler.post(() -> {
                            if (dup != null) {
                                Toast.makeText(activity, "Đã tạo bản sao: " + dup.getName(), Toast.LENGTH_SHORT).show();
                                reloadDirectory.run();
                            }
                        });
                    } catch (IOException e) {
                        mMainHandler.post(() -> Toast.makeText(activity, "Lỗi tạo bản sao: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
                return true;
            } else if (id == MENU_COMPRESS) {
                WorkspaceArchiveDialogs.showCompressDialog(activity, Collections.singletonList(item), mCurrentDir, reloadDirectory);
                return true;
            } else if (id == MENU_RENAME) {
                promptCreateName(activity, "Đổi tên", item.getName(), newName -> {
                    File dest = new File(item.getParentFile(), newName);
                    if (dest.exists()) {
                        Toast.makeText(activity, "Tên \"" + newName + "\" đã tồn tại", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (item.renameTo(dest)) {
                        mSelectedFilePaths.remove(item.getAbsolutePath());
                        reloadDirectory.run();
                    } else {
                        Toast.makeText(activity, "Không thể đổi tên", Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            } else if (id == MENU_COPY_PATH) {
                WorkspaceArchiveDialogs.copyPathToClipboard(activity, item);
                return true;
            } else if (id == MENU_DETAILS) {
                WorkspaceArchiveDialogs.showDetailsDialog(activity, item);
                return true;
            } else if (id == MENU_DELETE) {
                new AlertDialog.Builder(activity)
                    .setTitle("Xóa " + item.getName() + "?")
                    .setMessage(item.isDirectory() ? "Thư mục và toàn bộ nội dung bên trong sẽ bị xóa." : "Tệp này sẽ bị xóa vĩnh viễn.")
                    .setPositiveButton("Xóa", (d, w) -> mExecutor.execute(() -> {
                        deleteRecursively(item);
                        mMainHandler.post(() -> {
                            mSelectedFilePaths.remove(item.getAbsolutePath());
                            reloadDirectory.run();
                        });
                    }))
                    .setNegativeButton("Hủy", null)
                    .show();
                return true;
            }
            return false;
        });
        popup.show();
    }

    @NonNull
    private static List<File> collectEditableFiles(@NonNull File dir, int limit) {
        List<File> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return result;
        for (File f : children) {
            if (result.size() >= limit) break;
            if (f.isFile() && f.length() <= WorkspaceSessionStore.MAX_EDITABLE_FILE_BYTES) {
                result.add(f);
            }
        }
        return result;
    }

    private static void deleteRecursively(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private interface NameCallback {
        void onSubmit(@NonNull String name);
    }

    private static void promptCreateName(@NonNull Context context, @NonNull String title,
                                         @NonNull String initialValue, @NonNull NameCallback callback) {
        EditText input = new EditText(context);
        input.setText(initialValue);
        input.setSelectAllOnFocus(true);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(context);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Xác nhận", (dialog, which) -> {
                String value = input.getText() != null ? input.getText().toString().trim() : "";
                if (!value.isEmpty() && !value.contains("/")) {
                    callback.onSubmit(value);
                } else {
                    Toast.makeText(context, "Tên không hợp lệ", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private final class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private FileItemListener mListener;
        private final SimpleDateFormat mDateFormat = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

        void setListener(FileItemListener listener) {
            mListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_workspace_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            File file = mFilteredEntries.get(position);
            holder.name.setText(file.getName());

            if (file.isDirectory()) {
                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageResource(R.drawable.ic_workspace_folder);
                holder.icon.setColorFilter(Color.parseColor("#F59E0B"));
                holder.extBadge.setVisibility(View.GONE);
                holder.checkbox.setVisibility(mBatchSelectMode ? View.VISIBLE : View.GONE);
                holder.checkbox.setOnCheckedChangeListener(null);
                holder.checkbox.setChecked(mSelectedFilePaths.contains(file.getAbsolutePath()));
                holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                    if (mListener != null) mListener.onCheckboxToggle(file);
                });

                File[] children = file.listFiles();
                int count = children == null ? 0 : children.length;
                holder.meta.setText(count + " mục • " + mDateFormat.format(new Date(file.lastModified())));
            } else {
                String ext = getExtension(file.getName());
                if (!ext.isEmpty() && ext.length() <= 4) {
                    holder.icon.setVisibility(View.GONE);
                    holder.extBadge.setVisibility(View.VISIBLE);
                    holder.extBadge.setText(ext.toUpperCase(Locale.ROOT));
                    holder.extBadge.setTextColor(colorForExtension(ext));
                } else {
                    holder.icon.setVisibility(View.VISIBLE);
                    holder.icon.setImageResource(R.drawable.ic_workspace_file);
                    holder.icon.setColorFilter(Color.parseColor("#38BDF8"));
                    holder.extBadge.setVisibility(View.GONE);
                }

                holder.checkbox.setVisibility(View.VISIBLE);
                holder.checkbox.setOnCheckedChangeListener(null);
                holder.checkbox.setChecked(mSelectedFilePaths.contains(file.getAbsolutePath()));
                holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                    if (mListener != null) mListener.onCheckboxToggle(file);
                });

                holder.meta.setText(formatFileSize(file.length()) + " • " + mDateFormat.format(new Date(file.lastModified())));
            }

            holder.itemView.setOnClickListener(v -> {
                if (mListener != null) mListener.onItemClick(file);
            });

            holder.itemView.setOnLongClickListener(v -> {
                mBatchSelectMode = true;
                toggleSelection(file);
                notifyDataSetChanged();
                if (mListener != null) mListener.onCheckboxToggle(file);
                return true;
            });

            holder.moreBtn.setOnClickListener(v -> {
                if (mListener != null) mListener.onMoreClick(v, file);
            });
        }

        @Override
        public int getItemCount() {
            return mFilteredEntries.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView extBadge;
            final TextView name;
            final TextView meta;
            final CheckBox checkbox;
            final ImageButton moreBtn;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.item_file_icon);
                extBadge = itemView.findViewById(R.id.item_ext_badge);
                name = itemView.findViewById(R.id.item_file_name);
                meta = itemView.findViewById(R.id.item_file_meta);
                checkbox = itemView.findViewById(R.id.item_file_checkbox);
                moreBtn = itemView.findViewById(R.id.item_file_more);
            }
        }
    }

    @NonNull
    private static String getExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        return "";
    }

    private static int colorForExtension(@NonNull String ext) {
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "py": return Color.parseColor("#38BDF8");
            case "js":
            case "ts": return Color.parseColor("#FBBF24");
            case "sh":
            case "bash": return Color.parseColor("#10B981");
            case "kt":
            case "java": return Color.parseColor("#FB923C");
            case "json":
            case "yml":
            case "yaml": return Color.parseColor("#A78BFA");
            case "md":
            case "txt": return Color.parseColor("#9CA3AF");
            case "zip":
            case "tar":
            case "gz":
            case "tgz":
            case "rar":
            case "7z":
            case "apk":
            case "jar": return Color.parseColor("#C084FC");
            default: return Color.parseColor("#60A5FA");
        }
    }

    @NonNull
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

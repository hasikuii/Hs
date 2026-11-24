package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extra-key Workspace Menu displaying the files and folders in the current directory,
 * supporting remembered folder persistence, ZArchiver/RAR file & archive operations,
 * Pydroid-style multi-file batch editing, pasted tree diagrams, and batch archive operations.
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
    private static final int MENU_PREVIEW = 17;
    private static final int MENU_FAVORITE = 18;
    private static final int MENU_SHARE = 19;
    private static final int MENU_TOUCH = 20;
    private static final int MENU_HASH = 21;
    private static final String FAVORITES_PREFS = "workspace_file_manager";
    private static final String FAVORITES_KEY = "favorite_paths";

    // Sort modes — kept in sync with WorkspaceFileTools preference storage.
    private static final int SORT_NAME_ASC = 0;
    private static final int SORT_NAME_DESC = 1;
    private static final int SORT_SIZE_DESC = 2;
    private static final int SORT_SIZE_ASC = 3;
    private static final int SORT_MODIFIED_DESC = 4;
    private static final int SORT_MODIFIED_ASC = 5;
    private static final int SORT_TYPE_ASC = 6;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private File mCurrentDir;
    private final List<File> mAllEntries = new ArrayList<>();
    private final List<File> mFilteredEntries = new ArrayList<>();
    private final Set<String> mSelectedFilePaths = new HashSet<>();
    private boolean mBatchSelectMode = false;
    private boolean mShowHiddenEntries = false;
    private boolean mShowingFavorites = false;
    private final Set<String> mFavoritePaths = new HashSet<>();
    private String mFilterQuery = "";
    private int mSortMode = SORT_NAME_ASC;

    public void show(@NonNull Activity activity, @NonNull File initialDir, @NonNull HostActions hostActions) {
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        mCurrentDir = initialDir.isDirectory() ? initialDir : TermuxConstants.TERMUX_HOME_DIR;
        if (!mCurrentDir.exists()) {
            mCurrentDir = activity.getFilesDir();
        }
        store.setCurrentDirectory(activity, mCurrentDir);

        // Restore persistent file-manager preferences (sort mode + hidden-files flag).
        mShowHiddenEntries = WorkspaceFileTools.getShowHiddenPref(activity);
        mSortMode = WorkspaceFileTools.getSortModePref(activity);

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
        MaterialButton toolsBtn = root.findViewById(R.id.btn_open_file_tools);
        MaterialButton newFolderBtn = root.findViewById(R.id.btn_new_folder);
        MaterialButton favoritesBtn = root.findViewById(R.id.btn_workspace_favorites);

        loadFavoritePaths(activity);
        mShowingFavorites = false;

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
        MaterialButton extractBatchBtn = root.findViewById(R.id.btn_extract_selected_batch);
        MaterialButton deleteBatchBtn = root.findViewById(R.id.btn_delete_selected_batch);
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
            int archiveCount = 0;
            for (String path : mSelectedFilePaths) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    editableFileCount++;
                    if (WorkspaceArchiveHelper.isArchiveFile(f)) archiveCount++;
                }
            }
            if (totalCount > 0 || mBatchSelectMode) {
                batchBar.setVisibility(View.VISIBLE);
                boolean hasSelection = totalCount > 0;
                copyBatchBtn.setEnabled(hasSelection);
                moveBatchBtn.setEnabled(hasSelection);
                compressBatchBtn.setEnabled(hasSelection);
                extractBatchBtn.setEnabled(archiveCount > 0);
                deleteBatchBtn.setEnabled(hasSelection);
                extractBatchBtn.setText(archiveCount > 0
                    ? activity.getString(R.string.workspace_menu_action_extract, archiveCount)
                    : activity.getString(R.string.workspace_menu_action_extract_none));
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
            pathText.setText(mShowingFavorites ? "★ " + activity.getString(R.string.workspace_favorites_title) : mCurrentDir.getAbsolutePath());
            if (mShowingFavorites) {
                favoritesBtn.setText(R.string.workspace_favorites_back);
            } else {
                favoritesBtn.setText(activity.getString(R.string.workspace_favorites_button, mFavoritePaths.size()));
            }
            store.setCurrentDirectory(activity, mCurrentDir);
            if (!mShowingFavorites) {
                WorkspaceFileTools.recordRecentDir(activity, mCurrentDir);
            }
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
                    mShowingFavorites = false;
                    mCurrentDir = item;
                    mFilterQuery = "";
                    searchInput.setText("");
                    reloadDirectory.run();
                } else if (mBatchSelectMode) {
                    toggleSelection(item);
                    adapter.notifyDataSetChanged();
                    updateBatchBar.run();
                } else if (isPreviewableMedia(item)) {
                    openMediaPreview(activity, item);
                } else if (WorkspaceArchiveHelper.isArchiveFile(item)) {
                    File itemParent = item.getParentFile() != null ? item.getParentFile() : mCurrentDir;
                    WorkspaceArchiveDialogs.showArchiveQuickMenu(activity, item, itemParent, reloadDirectory, () -> {
                        dialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), itemParent);
                    });
                } else {
                    dialog.dismiss();
                    File itemParent = item.getParentFile() != null ? item.getParentFile() : mCurrentDir;
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), itemParent);
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

        favoritesBtn.setOnClickListener(v -> {
            mShowingFavorites = !mShowingFavorites;
            mFilterQuery = "";
            searchInput.setText("");
            reloadDirectory.run();
        });

        upBtn.setOnClickListener(v -> {
            mShowingFavorites = false;
            File parent = mCurrentDir.getParentFile();
            if (parent != null && parent.canRead()) {
                mCurrentDir = parent;
                reloadDirectory.run();
            }
        });

        homeBtn.setOnClickListener(v -> {
            mShowingFavorites = false;
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

        toolsBtn.setOnClickListener(v -> showAdvancedTools(activity, reloadDirectory, adapter, dialog, hostActions));

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

        extractBatchBtn.setOnClickListener(v -> {
            List<File> archives = new ArrayList<>();
            for (File selected : collectSelectedEntries()) {
                if (WorkspaceArchiveHelper.isArchiveFile(selected)) archives.add(selected);
            }
            if (archives.isEmpty()) return;
            new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.workspace_menu_action_extract, archives.size()))
                .setMessage(R.string.workspace_batch_extract_message)
                .setPositiveButton(R.string.workspace_archive_extract_here, (d, w) ->
                    WorkspaceArchiveDialogs.runExtractBatch(activity, archives, mCurrentDir, () -> {
                        mSelectedFilePaths.clear();
                        mBatchSelectMode = false;
                        reloadDirectory.run();
                    }))
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
        });

        deleteBatchBtn.setOnClickListener(v -> {
            List<File> selected = collectSelectedEntries();
            if (selected.isEmpty()) return;
            new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.workspace_batch_delete_title, selected.size()))
                .setMessage(R.string.workspace_batch_delete_message)
                .setPositiveButton(R.string.workspace_batch_delete_confirm, (d, w) ->
                    mExecutor.execute(() -> {
                        int deleted = 0;
                        int failed = 0;
                        for (File target : selected) {
                            if (deleteRecursively(target)) deleted++;
                            else failed++;
                        }
                        final int deletedCount = deleted;
                        final int failedCount = failed;
                        mMainHandler.post(() -> {
                            mSelectedFilePaths.clear();
                            mBatchSelectMode = false;
                            Toast.makeText(activity,
                                activity.getString(R.string.workspace_batch_delete_result, deletedCount, failedCount),
                                Toast.LENGTH_LONG).show();
                            reloadDirectory.run();
                        });
                    }))
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
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


    private void showAdvancedTools(@NonNull Activity activity, @NonNull Runnable reloadDirectory,
                                   @NonNull RecyclerView.Adapter<?> adapter,
                                   @NonNull BottomSheetDialog parentDialog,
                                   @NonNull HostActions hostActions) {
        final String[] actions = {
            "Sắp xếp: tên A → Z", "Sắp xếp: tên Z → A",
            "Sắp xếp: dung lượng lớn → nhỏ", "Sắp xếp: dung lượng nhỏ → lớn",
            "Sắp xếp: mới sửa gần đây", "Sắp xếp: cũ sửa trước",
            "Sắp xếp: theo loại tệp (đuôi file)",
            mShowHiddenEntries ? "Ẩn tệp ẩn" : "Hiện tệp ẩn",
            "Sao chép đường dẫn thư mục",
            "Chọn tất cả mục đang hiển thị", "Đảo ngược lựa chọn",
            "Tìm kiếm sâu trong thư mục và thư mục con",
            "Hiện thông tin ổ đĩa (dung lượng)",
            "Thư mục đã mở gần đây"
        };
        new AlertDialog.Builder(activity).setTitle("Công cụ quản lý tệp")
            .setItems(actions, (d, which) -> {
                switch (which) {
                    case 0:
                        mSortMode = SORT_NAME_ASC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 1:
                        mSortMode = SORT_NAME_DESC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 2:
                        mSortMode = SORT_SIZE_DESC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 3:
                        mSortMode = SORT_SIZE_ASC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 4:
                        mSortMode = SORT_MODIFIED_DESC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 5:
                        mSortMode = SORT_MODIFIED_ASC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 6:
                        mSortMode = SORT_TYPE_ASC;
                        WorkspaceFileTools.setSortModePref(activity, mSortMode);
                        break;
                    case 7:
                        mShowHiddenEntries = !mShowHiddenEntries;
                        WorkspaceFileTools.setShowHiddenPref(activity, mShowHiddenEntries);
                        reloadDirectory.run();
                        return;
                    case 8:
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("folder_path", mCurrentDir.getAbsolutePath()));
                            Toast.makeText(activity, "Đã sao chép đường dẫn thư mục", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    case 9:
                        for (File f : mFilteredEntries) mSelectedFilePaths.add(f.getAbsolutePath());
                        mBatchSelectMode = true; break;
                    case 10:
                        for (File f : mFilteredEntries) {
                            String p = f.getAbsolutePath();
                            if (!mSelectedFilePaths.remove(p)) mSelectedFilePaths.add(p);
                        }
                        mBatchSelectMode = true; break;
                    case 11:
                        showRecursiveSearchDialog(activity, mCurrentDir, result -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            try {
                                if (result.isDirectory()) {
                                    mShowingFavorites = false;
                                    mCurrentDir = result;
                                    mFilterQuery = "";
                                    reloadDirectory.run();
                                    Toast.makeText(activity, "Đã chuyển đến: " + result.getName(), Toast.LENGTH_SHORT).show();
                                } else if (isPreviewableMedia(result)) {
                                    openMediaPreview(activity, result);
                                } else if (WorkspaceArchiveHelper.isArchiveFile(result)) {
                                    File archParent = result.getParentFile() != null ? result.getParentFile() : mCurrentDir;
                                    WorkspaceArchiveDialogs.showArchiveQuickMenu(activity, result,
                                        archParent, reloadDirectory, () -> {
                                            if (parentDialog.isShowing()) parentDialog.dismiss();
                                            hostActions.onOpenFilesInBatchEditor(
                                                Collections.singletonList(result), archParent);
                                        });
                                } else if (result.length() > WorkspaceSessionStore.MAX_EDITABLE_FILE_BYTES) {
                                    Toast.makeText(activity, "Tệp quá lớn (" + formatFileSize(result.length()) + ") để mở trong trình sửa.", Toast.LENGTH_LONG).show();
                                } else {
                                    File fileParent = result.getParentFile() != null ? result.getParentFile() : mCurrentDir;
                                    if (parentDialog.isShowing()) parentDialog.dismiss();
                                    hostActions.onOpenFilesInBatchEditor(
                                        Collections.singletonList(result), fileParent);
                                }
                            } catch (Throwable t) {
                                // Catch Throwable (not just Exception) to handle OutOfMemoryError,
                                // StackOverflowError, and other Errors that would otherwise crash the app.
                                try {
                                    Toast.makeText(activity, "Không thể mở kết quả: " + t.getMessage(), Toast.LENGTH_LONG).show();
                                } catch (Throwable ignored) {}
                            }
                        });
                        return;
                    case 12:
                        WorkspaceFileTools.showStorageDialog(activity, mCurrentDir);
                        return;
                    case 13:
                        WorkspaceFileTools.showRecentDirsDialog(activity, dir -> {
                            mShowingFavorites = false;
                            mCurrentDir = dir;
                            mFilterQuery = "";
                            reloadDirectory.run();
                        });
                        return;
                }
                applySortMode();
                applyFilter();
                adapter.notifyDataSetChanged();
            }).show();
    }

    private static int compareDirsThen(@NonNull File a, @NonNull File b, int value) {
        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return value;
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
        if (mShowingFavorites) {
            for (String path : mFavoritePaths) {
                File favorite = new File(path);
                if (favorite.exists() && (mShowHiddenEntries || !favorite.isHidden())) {
                    mAllEntries.add(favorite);
                }
            }
        } else {
            File[] children = mCurrentDir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (mShowHiddenEntries || !child.isHidden()) mAllEntries.add(child);
            }
        }
        applySortMode();
    }

    /**
     * Re-sorts {@link #mAllEntries} according to the current {@link #mSortMode}.
     * Directories always come first; the secondary comparator depends on the mode.
     */
    private void applySortMode() {
        Collections.sort(mAllEntries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            switch (mSortMode) {
                case SORT_NAME_DESC:
                    return b.getName().compareToIgnoreCase(a.getName());
                case SORT_SIZE_DESC:
                    return Long.compare(b.length(), a.length());
                case SORT_SIZE_ASC:
                    return Long.compare(a.length(), b.length());
                case SORT_MODIFIED_DESC:
                    return Long.compare(b.lastModified(), a.lastModified());
                case SORT_MODIFIED_ASC:
                    return Long.compare(a.lastModified(), b.lastModified());
                case SORT_TYPE_ASC: {
                    String ea = getExtension(a.getName());
                    String eb = getExtension(b.getName());
                    int c = ea.compareToIgnoreCase(eb);
                    return c != 0 ? c : a.getName().compareToIgnoreCase(b.getName());
                }
                case SORT_NAME_ASC:
                default:
                    return a.getName().compareToIgnoreCase(b.getName());
            }
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
        popup.getMenu().add(0, MENU_TOUCH, order++, "Cập nhật thời gian sửa (touch)");
        if (!item.isDirectory()) {
            popup.getMenu().add(0, MENU_SHARE, order++, "Chia sẻ / Gửi tệp");
            popup.getMenu().add(0, MENU_HASH, order++, "Tính mã băm (MD5 / SHA-256)");
        }
        if (isPreviewableMedia(item)) {
            popup.getMenu().add(0, MENU_PREVIEW, order++, R.string.workspace_item_preview);
        }
        boolean isFavorite = mFavoritePaths.contains(item.getAbsolutePath());
        popup.getMenu().add(0, MENU_FAVORITE, order++,
            isFavorite ? R.string.workspace_favorite_remove : R.string.workspace_favorite_add);
        popup.getMenu().add(0, MENU_COPY_PATH, order++, R.string.workspace_item_copy_path);
        popup.getMenu().add(0, MENU_DETAILS, order++, R.string.workspace_item_details);
        popup.getMenu().add(0, MENU_DELETE, order, "Xóa");

        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        File itemParent = item.getParentFile() != null ? item.getParentFile() : mCurrentDir;
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
                WorkspaceArchiveDialogs.runExtract(activity, item, itemParent, reloadDirectory);
                return true;
            } else if (id == MENU_ARCHIVE_EXTRACT_SUB) {
                File sub = new File(itemParent, WorkspaceArchiveHelper.stripArchiveExtension(item.getName()));
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
                WorkspaceMoveHelper.showCopyDestinationPicker(activity, Collections.singletonList(item), itemParent,
                    (targetDir, copiedCount) -> reloadDirectory.run());
                return true;
            } else if (id == MENU_MOVE_TO) {
                WorkspaceMoveHelper.showDestinationPicker(activity, Collections.singletonList(item), itemParent,
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
                WorkspaceArchiveDialogs.showCompressDialog(activity, Collections.singletonList(item), itemParent, reloadDirectory);
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
            } else if (id == MENU_TOUCH) {
                if (WorkspaceFileTools.touchNow(item)) {
                    Toast.makeText(activity, "Đã cập nhật thời gian sửa của \"" + item.getName() + "\"",
                        Toast.LENGTH_SHORT).show();
                    reloadDirectory.run();
                } else {
                    Toast.makeText(activity, "Không thể cập nhật thời gian sửa (có thể do quyền truy cập)",
                        Toast.LENGTH_LONG).show();
                }
                return true;
            } else if (id == MENU_SHARE) {
                WorkspaceFileTools.shareFile(activity, item);
                return true;
            } else if (id == MENU_HASH) {
                WorkspaceFileTools.showHashDialog(activity, item);
                return true;
            } else if (id == MENU_PREVIEW) {
                openMediaPreview(activity, item);
                return true;
            } else if (id == MENU_FAVORITE) {
                String path = item.getAbsolutePath();
                if (mFavoritePaths.contains(path)) {
                    mFavoritePaths.remove(path);
                    Toast.makeText(activity, R.string.workspace_favorite_removed, Toast.LENGTH_SHORT).show();
                } else {
                    mFavoritePaths.add(path);
                    Toast.makeText(activity, R.string.workspace_favorite_added, Toast.LENGTH_SHORT).show();
                }
                saveFavoritePaths(activity);
                reloadDirectory.run();
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

    private void loadFavoritePaths(@NonNull Context context) {
        java.util.Set<String> stored = context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .getStringSet(FAVORITES_KEY, Collections.emptySet());
        mFavoritePaths.clear();
        if (stored != null) mFavoritePaths.addAll(stored);
    }

    private void saveFavoritePaths(@NonNull Context context) {
        context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(FAVORITES_KEY, new HashSet<>(mFavoritePaths)).apply();
    }

    private interface FileResultCallback {
        void onSelected(@NonNull File file);
    }

    private void showRecursiveSearchDialog(@NonNull Activity activity, @NonNull File root,
                                            @NonNull FileResultCallback callback) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.workspace_recursive_search_hint);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(activity);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle(R.string.workspace_recursive_search_title)
            .setMessage(activity.getString(R.string.workspace_recursive_search_root, root.getAbsolutePath()))
            .setView(container)
            .setPositiveButton(R.string.workspace_recursive_search_action, (dialog, which) -> {
                String query = input.getText() == null ? "" : input.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty()) {
                    Toast.makeText(activity, R.string.workspace_recursive_search_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                ProgressDialog progress = new ProgressDialog(activity);
                progress.setTitle(R.string.workspace_recursive_search_title);
                progress.setMessage(activity.getString(R.string.workspace_recursive_search_progress));
                progress.setIndeterminate(true);
                progress.setCancelable(true);
                AtomicBoolean cancelled = new AtomicBoolean(false);
                progress.setOnCancelListener(d -> cancelled.set(true));
                progress.show();

                mExecutor.execute(() -> {
                    List<File> matches = new ArrayList<>();
                    collectRecursiveMatches(root, query, matches, cancelled, 0, 500);
                    mMainHandler.post(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (progress.isShowing()) progress.dismiss();
                        if (cancelled.get()) return;
                        if (matches.isEmpty()) {
                            new AlertDialog.Builder(activity)
                                .setMessage(R.string.workspace_recursive_search_no_results)
                                .setPositiveButton(R.string.editor_cancel, null)
                                .show();
                            return;
                        }
                        List<String> labels = new ArrayList<>();
                        for (File match : matches) labels.add(match.getAbsolutePath());
                        ArrayAdapter<String> resultsAdapter = new ArrayAdapter<>(
                            activity, android.R.layout.simple_list_item_1, labels);
                        new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.workspace_recursive_search_results, matches.size()))
                            .setAdapter(resultsAdapter, (resultDialog, index) -> callback.onSelected(matches.get(index)))
                            .setNegativeButton(R.string.editor_cancel, null)
                            .show();
                    });
                });
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void collectRecursiveMatches(@NonNull File directory, @NonNull String query,
                                         @NonNull List<File> matches, @NonNull AtomicBoolean cancelled,
                                         int depth, int maxResults) {
        if (cancelled.get() || depth > 64 || matches.size() >= maxResults ||
            !directory.isDirectory() || java.nio.file.Files.isSymbolicLink(directory.toPath())) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (cancelled.get() || matches.size() >= maxResults) return;
            if (!mShowHiddenEntries && child.isHidden()) continue;
            if (child.getName().toLowerCase(Locale.ROOT).contains(query)) matches.add(child);
            if (child.isDirectory() && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                collectRecursiveMatches(child, query, matches, cancelled, depth + 1, maxResults);
            }
        }
    }

    private static boolean isPreviewableMedia(@NonNull File file) {
        String mime = getMimeType(file);
        return mime != null && (mime.startsWith("image/") || mime.startsWith("video/"));
    }

    private static String getMimeType(@NonNull File file) {
        String extension = getExtension(file.getName()).toLowerCase(Locale.ROOT);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    private static void openMediaPreview(@NonNull Activity activity, @NonNull File file) {
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".workspace.fileprovider", file);
            String mime = getMimeType(file);
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, mime != null ? mime : "*/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(viewIntent,
                activity.getString(R.string.workspace_item_preview)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.workspace_preview_no_app, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity, R.string.workspace_preview_path_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean deleteRecursively(@NonNull File file) {
        // Delete a symbolic link itself; never traverse its target.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return file.delete();
        }
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteRecursively(child)) success = false;
            }
        }
        return file.delete() && success;
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
                holder.meta.setText((mFavoritePaths.contains(file.getAbsolutePath()) ? "★ " : "") +
                    count + " mục • " + mDateFormat.format(new Date(file.lastModified())));
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

                holder.checkbox.setVisibility(mBatchSelectMode ? View.VISIBLE : View.GONE);
                holder.checkbox.setOnCheckedChangeListener(null);
                holder.checkbox.setChecked(mSelectedFilePaths.contains(file.getAbsolutePath()));
                holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                    if (mListener != null) mListener.onCheckboxToggle(file);
                });

                holder.meta.setText((mFavoritePaths.contains(file.getAbsolutePath()) ? "★ " : "") +
                    formatFileSize(file.length()) + " • " + mDateFormat.format(new Date(file.lastModified())));
            }

            holder.itemView.setOnClickListener(v -> {
                if (mListener != null) mListener.onItemClick(file);
            });

            holder.itemView.setOnLongClickListener(v -> {
                mBatchSelectMode = true;
                if (mListener != null) mListener.onCheckboxToggle(file);
                notifyDataSetChanged();
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

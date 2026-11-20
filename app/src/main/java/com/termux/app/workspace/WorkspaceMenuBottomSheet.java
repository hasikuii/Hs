package com.termux.app.workspace;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
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

/**
 * Extra-key Workspace Menu displaying the files and folders in the current directory,
 * supporting tap-to-open, Pydroid-style multi-file batch selection & editing,
 * and quick creation via pasted tree diagrams.
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

    private File mCurrentDir;
    private final List<File> mAllEntries = new ArrayList<>();
    private final List<File> mFilteredEntries = new ArrayList<>();
    private final Set<String> mSelectedFilePaths = new HashSet<>();
    private boolean mBatchSelectMode = false;
    private String mFilterQuery = "";

    public void show(@NonNull Context context, @NonNull File initialDir, @NonNull HostActions hostActions) {
        mCurrentDir = initialDir.isDirectory() ? initialDir : TermuxConstants.TERMUX_HOME_DIR;
        if (!mCurrentDir.exists()) {
            mCurrentDir = context.getFilesDir();
        }
        WorkspaceSessionStore.getInstance().setCurrentDirectory(mCurrentDir);

        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.Theme_WorkspaceBottomSheet);
        View root = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_workspace_menu, null);
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
        MaterialButton newFolderBtn = root.findViewById(R.id.btn_new_folder);

        EditText searchInput = root.findViewById(R.id.workspace_search_input);
        RecyclerView recyclerView = root.findViewById(R.id.workspace_file_list);
        View emptyView = root.findViewById(R.id.workspace_empty_view);
        LinearLayout batchBar = root.findViewById(R.id.workspace_batch_bar);
        MaterialButton batchClearBtn = root.findViewById(R.id.btn_batch_clear);
        MaterialButton openBatchBtn = root.findViewById(R.id.btn_open_selected_batch);

        FileAdapter adapter = new FileAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);

        Runnable updateBatchBar = () -> {
            int count = mSelectedFilePaths.size();
            if (count > 0 || mBatchSelectMode) {
                batchBar.setVisibility(View.VISIBLE);
                if (count > 0) {
                    openBatchBtn.setEnabled(true);
                    openBatchBtn.setText(context.getString(R.string.workspace_menu_action_open_batch, count));
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
            WorkspaceSessionStore.getInstance().setCurrentDirectory(mCurrentDir);
            loadDirectoryEntries();
            applyFilter();
            adapter.notifyDataSetChanged();
            emptyView.setVisibility(mFilteredEntries.isEmpty() ? View.VISIBLE : View.GONE);
            updateBatchBar.run();
        };

        adapter.setListener(new FileItemListener() {
            @Override
            public void onItemClick(@NonNull File item) {
                if (item.isDirectory()) {
                    mCurrentDir = item;
                    mFilterQuery = "";
                    searchInput.setText("");
                    reloadDirectory.run();
                } else {
                    if (mBatchSelectMode) {
                        toggleSelection(item);
                        adapter.notifyDataSetChanged();
                        updateBatchBar.run();
                    } else {
                        dialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), mCurrentDir);
                    }
                }
            }

            @Override
            public void onCheckboxToggle(@NonNull File item) {
                if (item.isDirectory()) return;
                toggleSelection(item);
                updateBatchBar.run();
            }

            @Override
            public void onMoreClick(@NonNull View anchor, @NonNull File item) {
                showItemPopupMenu(context, anchor, item, dialog, hostActions, reloadDirectory);
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
            Toast.makeText(context, context.getString(R.string.workspace_menu_cd_synced, mCurrentDir.getAbsolutePath()), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        refreshBtn.setOnClickListener(v -> reloadDirectory.run());

        treeBtn.setOnClickListener(v -> {
            new TreeCreatorBottomSheet().show(context, mCurrentDir, (dir, createdFiles, openInEditor) -> {
                reloadDirectory.run();
                if (openInEditor && !createdFiles.isEmpty()) {
                    dialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(createdFiles, mCurrentDir);
                }
            });
        });

        batchToggleBtn.setOnClickListener(v -> {
            mBatchSelectMode = !mBatchSelectMode;
            if (!mBatchSelectMode && mSelectedFilePaths.isEmpty()) {
                mSelectedFilePaths.clear();
            }
            updateBatchBar.run();
            adapter.notifyDataSetChanged();
        });

        newFileBtn.setOnClickListener(v -> promptCreateName(context, "Tạo tệp mới", "example.py", name -> {
            File target = new File(mCurrentDir, name);
            try {
                if (target.exists() || target.createNewFile()) {
                    reloadDirectory.run();
                    dialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(target), mCurrentDir);
                }
            } catch (IOException e) {
                Toast.makeText(context, "Lỗi tạo tệp: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));

        newFolderBtn.setOnClickListener(v -> promptCreateName(context, "Tạo thư mục mới", "new_folder", name -> {
            File target = new File(mCurrentDir, name);
            if (!target.exists() && !target.mkdirs()) {
                Toast.makeText(context, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show();
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

        batchClearBtn.setOnClickListener(v -> {
            mSelectedFilePaths.clear();
            mBatchSelectMode = false;
            adapter.notifyDataSetChanged();
            updateBatchBar.run();
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
        for (File child : children) {
            mAllEntries.add(child);
        }
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

    private void showItemPopupMenu(@NonNull Context context, @NonNull View anchor, @NonNull File item,
                                   @NonNull BottomSheetDialog parentDialog, @NonNull HostActions hostActions,
                                   @NonNull Runnable reloadDirectory) {
        PopupMenu popup = new PopupMenu(context, anchor);
        if (item.isDirectory()) {
            popup.getMenu().add(0, 1, 0, "Mở sửa tất cả tệp bên trong");
            popup.getMenu().add(0, 2, 1, "Dán sơ đồ cây vào thư mục này");
            popup.getMenu().add(0, 3, 2, "cd vào thư mục trong Terminal");
            popup.getMenu().add(0, 4, 3, "Đổi tên");
            popup.getMenu().add(0, 5, 4, "Xóa");
        } else {
            popup.getMenu().add(0, 1, 0, "Mở chỉnh sửa");
            popup.getMenu().add(0, 4, 1, "Đổi tên");
            popup.getMenu().add(0, 5, 2, "Xóa");
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == 1) {
                if (item.isDirectory()) {
                    List<File> files = collectEditableFiles(item, 25);
                    if (files.isEmpty()) {
                        Toast.makeText(context, "Không có tệp nào trong thư mục này", Toast.LENGTH_SHORT).show();
                    } else {
                        parentDialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(files, item);
                    }
                } else {
                    parentDialog.dismiss();
                    hostActions.onOpenFilesInBatchEditor(Collections.singletonList(item), mCurrentDir);
                }
                return true;
            } else if (id == 2 && item.isDirectory()) {
                new TreeCreatorBottomSheet().show(context, item, (dir, createdFiles, openInEditor) -> {
                    reloadDirectory.run();
                    if (openInEditor && !createdFiles.isEmpty()) {
                        parentDialog.dismiss();
                        hostActions.onOpenFilesInBatchEditor(createdFiles, item);
                    }
                });
                return true;
            } else if (id == 3 && item.isDirectory()) {
                hostActions.onSendCdCommandToTerminal(item);
                parentDialog.dismiss();
                return true;
            } else if (id == 4) {
                promptCreateName(context, "Đổi tên", item.getName(), newName -> {
                    File dest = new File(item.getParentFile(), newName);
                    if (item.renameTo(dest)) {
                        reloadDirectory.run();
                    } else {
                        Toast.makeText(context, "Không thể đổi tên", Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            } else if (id == 5) {
                new AlertDialog.Builder(context)
                    .setTitle("Xóa " + item.getName() + "?")
                    .setMessage(item.isDirectory() ? "Thư mục và toàn bộ nội dung bên trong sẽ bị xóa." : "Tệp này sẽ bị xóa vĩnh viễn.")
                    .setPositiveButton("Xóa", (d, w) -> {
                        deleteRecursively(item);
                        mSelectedFilePaths.remove(item.getAbsolutePath());
                        reloadDirectory.run();
                    })
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
                holder.checkbox.setVisibility(View.GONE);

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
                if (!file.isDirectory()) {
                    mBatchSelectMode = true;
                    toggleSelection(file);
                    notifyDataSetChanged();
                    if (mListener != null) mListener.onCheckboxToggle(file);
                    return true;
                }
                return false;
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

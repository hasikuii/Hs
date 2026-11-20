package com.termux.app.workspace;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper for browsing destination directories and moving files/folders safely,
 * including cycle checks, conflict checks, and cross-filesystem copy+delete fallback.
 */
public final class WorkspaceMoveHelper {

    public interface MoveCallback {
        void onMoveCompleted(@NonNull File targetDir, int movedCount);
    }

    public interface CopyCallback {
        void onCopyCompleted(@NonNull File targetDir, int copiedCount);
    }

    public interface FolderPickCallback {
        void onFolderPicked(@NonNull File destDir);
    }

    private interface FolderConfirmHandler {
        boolean onConfirm(@NonNull AlertDialog dialog, @NonNull File destDir);
    }

    public static final class MoveResult {
        public final int movedCount;
        public final int skippedCount;
        @Nullable public final String lastError;

        public MoveResult(int movedCount, int skippedCount, @Nullable String lastError) {
            this.movedCount = movedCount;
            this.skippedCount = skippedCount;
            this.lastError = lastError;
        }
    }

    private WorkspaceMoveHelper() {}

    public static void showDestinationPicker(@NonNull Context context,
                                             @NonNull List<File> itemsToMove,
                                             @NonNull File initialDir,
                                             @NonNull MoveCallback callback) {
        if (itemsToMove.isEmpty()) return;

        Set<String> blockedSubtrees = collectBlockedSubtrees(itemsToMove);
        String title = itemsToMove.size() == 1
            ? context.getString(R.string.workspace_move_title_single, itemsToMove.get(0).getName())
            : context.getString(R.string.workspace_move_title_multi, itemsToMove.size());

        showFolderBrowserInternal(context, initialDir, blockedSubtrees, title,
            context.getString(R.string.workspace_move_here), (dialog, destDir) -> {
                MoveResult res = moveItems(context, itemsToMove, destDir);
                if (res.movedCount > 0) {
                    if (res.skippedCount == 0) {
                        Toast.makeText(context,
                            context.getString(R.string.workspace_move_success, res.movedCount, destDir.getName()),
                            Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context,
                            context.getString(R.string.workspace_move_partial,
                                res.movedCount, itemsToMove.size(), res.skippedCount),
                            Toast.LENGTH_LONG).show();
                    }
                    callback.onMoveCompleted(destDir, res.movedCount);
                    return true;
                } else {
                    String msg = res.lastError != null
                        ? res.lastError
                        : context.getString(R.string.workspace_move_same_dir);
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    return false;
                }
            });
    }

    public static void showCopyDestinationPicker(@NonNull Context context,
                                                 @NonNull List<File> itemsToCopy,
                                                 @NonNull File initialDir,
                                                 @NonNull CopyCallback callback) {
        if (itemsToCopy.isEmpty()) return;

        Set<String> blockedSubtrees = collectBlockedSubtrees(itemsToCopy);
        String title = itemsToCopy.size() == 1
            ? "Sao chép \"" + itemsToCopy.get(0).getName() + "\" đến:"
            : "Sao chép " + itemsToCopy.size() + " mục đến:";

        showFolderBrowserInternal(context, initialDir, blockedSubtrees, title,
            "Sao chép vào đây", (dialog, destDir) -> {
                WorkspaceArchiveHelper.CopyResult res = WorkspaceArchiveHelper.copyItems(itemsToCopy, destDir, true);
                if (res.copiedCount > 0) {
                    Toast.makeText(context,
                        "Đã sao chép " + res.copiedCount + " mục tới " + destDir.getName(),
                        Toast.LENGTH_SHORT).show();
                    callback.onCopyCompleted(destDir, res.copiedCount);
                    return true;
                } else {
                    String msg = res.lastError != null ? res.lastError : "Không thể sao chép vào thư mục này";
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    return false;
                }
            });
    }

    public static void showPickFolderOnlyDialog(@NonNull Context context,
                                                @NonNull File initialDir,
                                                @NonNull String title,
                                                @NonNull String positiveLabel,
                                                @NonNull FolderPickCallback callback) {
        showFolderBrowserInternal(context, initialDir, Collections.emptySet(), title,
            positiveLabel, (dialog, destDir) -> {
                callback.onFolderPicked(destDir);
                return true;
            });
    }

    @NonNull
    private static Set<String> collectBlockedSubtrees(@NonNull List<File> items) {
        Set<String> blockedSubtrees = new HashSet<>();
        for (File item : items) {
            if (item.isDirectory()) {
                try {
                    blockedSubtrees.add(item.getCanonicalPath());
                } catch (IOException ignored) {
                    blockedSubtrees.add(item.getAbsolutePath());
                }
            }
        }
        return blockedSubtrees;
    }

    private static void showFolderBrowserInternal(@NonNull Context context,
                                                  @NonNull File initialDir,
                                                  @NonNull Set<String> blockedSubtrees,
                                                  @NonNull String titleText,
                                                  @NonNull String positiveLabel,
                                                  @NonNull FolderConfirmHandler confirmHandler) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad16 = (int) (16 * density);
        final int pad12 = (int) (12 * density);
        final int pad8 = (int) (8 * density);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad16, pad12, pad16, pad8);
        container.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout navRow = new LinearLayout(context);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton upBtn = new ImageButton(context);
        upBtn.setImageResource(R.drawable.ic_workspace_up);
        upBtn.setColorFilter(Color.parseColor("#C9D1D9"));
        upBtn.setBackgroundColor(Color.TRANSPARENT);
        upBtn.setContentDescription(context.getString(R.string.workspace_menu_up_desc));
        int btnSize = (int) (48 * density);
        navRow.addView(upBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        ImageButton homeBtn = new ImageButton(context);
        homeBtn.setImageResource(R.drawable.ic_workspace_home);
        homeBtn.setColorFilter(Color.parseColor("#C9D1D9"));
        homeBtn.setBackgroundColor(Color.TRANSPARENT);
        homeBtn.setContentDescription(context.getString(R.string.workspace_menu_home_desc));
        navRow.addView(homeBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        TextView pathView = new TextView(context);
        pathView.setTextColor(Color.parseColor("#38BDF8"));
        pathView.setTextSize(12f);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pathLp.setMargins(pad8, 0, pad8, 0);
        navRow.addView(pathView, pathLp);

        TextView newDirBtn = new TextView(context);
        newDirBtn.setText(R.string.workspace_menu_action_new_folder);
        newDirBtn.setTextColor(Color.parseColor("#F59E0B"));
        newDirBtn.setTextSize(12f);
        newDirBtn.setTypeface(Typeface.DEFAULT_BOLD);
        newDirBtn.setPadding(pad8, pad8, pad8, pad8);
        newDirBtn.setMinHeight(btnSize);
        newDirBtn.setGravity(Gravity.CENTER);
        navRow.addView(newDirBtn);

        container.addView(navRow);

        TextView emptyLabel = new TextView(context);
        emptyLabel.setText(R.string.workspace_move_empty_dirs);
        emptyLabel.setTextColor(Color.parseColor("#8B949E"));
        emptyLabel.setTextSize(13f);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(pad12, pad16, pad12, pad16);
        emptyLabel.setVisibility(View.GONE);
        container.addView(emptyLabel);

        ListView folderList = new ListView(context);
        folderList.setDivider(null);
        folderList.setDividerHeight((int) (4 * density));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int) (260 * density));
        listLp.topMargin = pad8;
        container.addView(folderList, listLp);

        final File[] browsedDir = { initialDir.isDirectory() ? initialDir : TermuxConstants.TERMUX_HOME_DIR };
        final List<File> subDirs = new ArrayList<>();

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return subDirs.size();
            }

            @Override
            public File getItem(int position) {
                return subDirs.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                ImageView icon;
                TextView title;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                    icon = (ImageView) row.getChildAt(0);
                    title = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(pad12, pad8, pad12, pad8);
                    row.setMinimumHeight((int) (48 * density));
                    row.setBackgroundColor(Color.parseColor("#161B22"));

                    icon = new ImageView(context);
                    icon.setImageResource(R.drawable.ic_workspace_folder);
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                        (int) (22 * density), (int) (22 * density));
                    iconLp.setMarginEnd(pad12);
                    row.addView(icon, iconLp);

                    title = new TextView(context);
                    title.setTextSize(14f);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    row.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                File dir = getItem(position);
                boolean blocked = isInsideBlockedSubtree(dir, blockedSubtrees);
                icon.setColorFilter(Color.parseColor(blocked ? "#4B5563" : "#F59E0B"));
                title.setText(dir.getName());
                title.setTextColor(Color.parseColor(blocked ? "#6E7681" : "#F0F6FC"));
                return row;
            }
        };
        folderList.setAdapter(adapter);

        Runnable reload = () -> {
            pathView.setText(browsedDir[0].getAbsolutePath());
            subDirs.clear();
            File[] children = browsedDir[0].listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) {
                        subDirs.add(c);
                    }
                }
                Collections.sort(subDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            }
            adapter.notifyDataSetChanged();
            emptyLabel.setVisibility(subDirs.isEmpty() ? View.VISIBLE : View.GONE);
        };

        folderList.setOnItemClickListener((parent, view, position, id) -> {
            File target = subDirs.get(position);
            if (isInsideBlockedSubtree(target, blockedSubtrees)) {
                Toast.makeText(context, R.string.workspace_move_into_self, Toast.LENGTH_SHORT).show();
                return;
            }
            browsedDir[0] = target;
            reload.run();
        });

        upBtn.setOnClickListener(v -> {
            File parent = browsedDir[0].getParentFile();
            if (parent != null && parent.canRead()) {
                browsedDir[0] = parent;
                reload.run();
            }
        });

        homeBtn.setOnClickListener(v -> {
            if (TermuxConstants.TERMUX_HOME_DIR.exists()) {
                browsedDir[0] = TermuxConstants.TERMUX_HOME_DIR;
                reload.run();
            }
        });

        newDirBtn.setOnClickListener(v -> {
            EditText input = new EditText(context);
            input.setHint(R.string.workspace_move_new_folder_hint);
            input.setSingleLine(true);
            LinearLayout inputBox = new LinearLayout(context);
            inputBox.setPadding(pad16, pad8, pad16, 0);
            inputBox.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(context)
                .setTitle(R.string.workspace_menu_action_new_folder)
                .setView(inputBox)
                .setPositiveButton("Tạo", (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty() || name.contains("/") || ".".equals(name) || "..".equals(name)) {
                        Toast.makeText(context, "Tên thư mục không hợp lệ", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File newDir = new File(browsedDir[0], name);
                    if (newDir.exists() || newDir.mkdirs()) {
                        browsedDir[0] = newDir;
                        reload.run();
                    } else {
                        Toast.makeText(context, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
        });

        reload.run();

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(titleText)
            .setView(container)
            .setPositiveButton(positiveLabel, null)
            .setNegativeButton(R.string.editor_cancel, null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (confirmHandler.onConfirm(dialog, browsedDir[0])) {
                dialog.dismiss();
            }
        }));

        dialog.show();
    }

    @NonNull
    public static MoveResult moveItems(@Nullable Context context,
                                       @NonNull List<File> items,
                                       @NonNull File destDir) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            return new MoveResult(0, items.size(), "Không thể tạo thư mục đích");
        }
        if (!destDir.isDirectory()) {
            return new MoveResult(0, items.size(), "Đích đến không phải là thư mục");
        }

        String destCanonical;
        try {
            destCanonical = destDir.getCanonicalPath();
        } catch (IOException e) {
            destCanonical = destDir.getAbsolutePath();
        }

        int moved = 0;
        int skipped = 0;
        String lastError = null;

        for (File source : items) {
            if (!source.exists()) {
                skipped++;
                continue;
            }
            File parent = source.getParentFile();
            if (parent != null) {
                try {
                    if (parent.getCanonicalPath().equals(destCanonical)) {
                        skipped++;
                        lastError = context != null
                            ? context.getString(R.string.workspace_move_same_dir)
                            : "Mục đã nằm trong thư mục này rồi";
                        continue;
                    }
                } catch (IOException ignored) {
                    if (parent.getAbsolutePath().equals(destDir.getAbsolutePath())) {
                        skipped++;
                        lastError = context != null
                            ? context.getString(R.string.workspace_move_same_dir)
                            : "Mục đã nằm trong thư mục này rồi";
                        continue;
                    }
                }
            }

            if (source.isDirectory()) {
                String srcCanonical;
                try {
                    srcCanonical = source.getCanonicalPath();
                } catch (IOException e) {
                    srcCanonical = source.getAbsolutePath();
                }
                if (destCanonical.equals(srcCanonical) || destCanonical.startsWith(srcCanonical + File.separator)) {
                    skipped++;
                    lastError = context != null
                        ? context.getString(R.string.workspace_move_into_self)
                        : "Không thể di chuyển thư mục vào chính nó hoặc thư mục con";
                    continue;
                }
            }

            File target = new File(destDir, source.getName());
            if (target.exists()) {
                skipped++;
                lastError = context != null
                    ? context.getString(R.string.workspace_move_conflict, source.getName())
                    : "Tên \"" + source.getName() + "\" đã tồn tại ở thư mục đích";
                continue;
            }

            if (moveSingleEntry(source, target)) {
                moved++;
            } else {
                skipped++;
                lastError = "Không thể di chuyển " + source.getName();
            }
        }

        return new MoveResult(moved, skipped, lastError);
    }

    private static boolean moveSingleEntry(@NonNull File source, @NonNull File target) {
        if (source.renameTo(target)) {
            return true;
        }
        try {
            copyRecursively(source, target);
            deleteRecursively(source);
            return target.exists();
        } catch (IOException e) {
            deleteRecursively(target);
            return false;
        }
    }

    private static void copyRecursively(@NonNull File source, @NonNull File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Cannot create directory: " + target);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
        } else {
            byte[] buf = new byte[16384];
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
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

    private static boolean isInsideBlockedSubtree(@NonNull File candidateDir, @NonNull Set<String> blockedSubtrees) {
        if (blockedSubtrees.isEmpty()) return false;
        String candidateCanonical;
        try {
            candidateCanonical = candidateDir.getCanonicalPath();
        } catch (IOException e) {
            candidateCanonical = candidateDir.getAbsolutePath();
        }
        for (String blocked : blockedSubtrees) {
            if (candidateCanonical.equals(blocked) || candidateCanonical.startsWith(blocked + File.separator)) {
                return true;
            }
        }
        return false;
    }
}

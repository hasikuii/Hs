package com.termux.app.workspace;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Bottom sheet allowing the user to paste any directory tree diagram,
 * preview/adjust inferred folders & files, and create the entire structure in one tap.
 */
public final class TreeCreatorBottomSheet {

    public interface Callback {
        void onStructureCreated(@NonNull File targetDir, @NonNull List<File> createdFiles, boolean openInEditor);
    }

    private static final String[] SAMPLE_TREES = new String[] {
        "my-python-app/\n" +
            "├── app/\n" +
            "│   ├── __init__.py\n" +
            "│   ├── main.py         # Entry point\n" +
            "│   └── utils/\n" +
            "│       └── helpers.py\n" +
            "├── tests/\n" +
            "│   └── test_main.py\n" +
            "├── requirements.txt\n" +
            "└── README.md",

        "web-starter/\n" +
            "+-- src/\n" +
            "|   +-- index.html\n" +
            "|   +-- styles.css\n" +
            "|   \\-- app.js\n" +
            "`-- package.json",

        "- api-service/\n" +
            "  - routes/\n" +
            "    - auth.py\n" +
            "    - users.py\n" +
            "  - config.yaml\n" +
            "  - Dockerfile"
    };

    private int mSampleIndex = 0;

    public void show(@NonNull Context context, @NonNull File targetDir, @Nullable Callback callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.Theme_WorkspaceBottomSheet);
        View root = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_tree_creator, null);
        dialog.setContentView(root);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        TextView targetPathView = root.findViewById(R.id.tree_target_path);
        EditText inputEdit = root.findViewById(R.id.tree_input_edit);
        CheckBox includeRootCheck = root.findViewById(R.id.tree_include_root_checkbox);
        TextView summaryView = root.findViewById(R.id.tree_preview_summary);
        RecyclerView previewRecycler = root.findViewById(R.id.tree_preview_list);
        MaterialButton pasteBtn = root.findViewById(R.id.btn_tree_paste);
        MaterialButton sampleBtn = root.findViewById(R.id.btn_tree_sample);
        MaterialButton clearBtn = root.findViewById(R.id.btn_tree_clear);
        MaterialButton createBtn = root.findViewById(R.id.btn_tree_create);
        MaterialButton openCreatedBtn = root.findViewById(R.id.btn_tree_open_created);

        targetPathView.setText(context.getString(R.string.tree_creator_subtitle, targetDir.getAbsolutePath()));

        List<TreeStructureParser.TreeEntry> parsedEntries = new ArrayList<>();
        List<File> lastCreatedFiles = new ArrayList<>();

        PreviewAdapter adapter = new PreviewAdapter(parsedEntries, () -> updateSummary(context, summaryView, parsedEntries));
        previewRecycler.setLayoutManager(new LinearLayoutManager(context));
        previewRecycler.setAdapter(adapter);

        Runnable reparse = () -> {
            String text = inputEdit.getText() != null ? inputEdit.getText().toString() : "";
            List<TreeStructureParser.TreeEntry> fresh = TreeStructureParser.parse(text, includeRootCheck.isChecked());
            TreeStructureParser.markExisting(targetDir, fresh);
            parsedEntries.clear();
            parsedEntries.addAll(fresh);
            adapter.notifyDataSetChanged();
            updateSummary(context, summaryView, parsedEntries);
            createBtn.setEnabled(!parsedEntries.isEmpty());
            openCreatedBtn.setVisibility(View.GONE);
            lastCreatedFiles.clear();
        };

        inputEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                reparse.run();
            }
        });

        includeRootCheck.setOnCheckedChangeListener((buttonView, isChecked) -> reparse.run());

        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence pasteText = clip.getItemAt(0).coerceToText(context);
                    if (pasteText != null) {
                        inputEdit.setText(pasteText.toString());
                        inputEdit.setSelection(inputEdit.getText().length());
                        return;
                    }
                }
            }
            Toast.makeText(context, "Clipboard trống", Toast.LENGTH_SHORT).show();
        });

        sampleBtn.setOnClickListener(v -> {
            String sample = SAMPLE_TREES[mSampleIndex % SAMPLE_TREES.length];
            mSampleIndex++;
            inputEdit.setText(sample);
            inputEdit.setSelection(inputEdit.getText().length());
        });

        clearBtn.setOnClickListener(v -> inputEdit.setText(""));

        createBtn.setEnabled(false);
        createBtn.setOnClickListener(v -> {
            if (parsedEntries.isEmpty()) return;
            createBtn.setEnabled(false);
            final List<TreeStructureParser.TreeEntry> snapshot = new ArrayList<>(parsedEntries);
            Executors.newSingleThreadExecutor().execute(() -> {
                TreeStructureParser.CreationResult res = TreeStructureParser.createStructure(targetDir, snapshot);
                new Handler(Looper.getMainLooper()).post(() -> {
                    createBtn.setEnabled(true);
                    TreeStructureParser.markExisting(targetDir, parsedEntries);
                    adapter.notifyDataSetChanged();
                    updateSummary(context, summaryView, parsedEntries);

                    String msg = context.getString(R.string.tree_creator_success,
                        res.createdDirs, res.createdFiles, res.skippedExisting);
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();

                    if (!res.errors.isEmpty()) {
                        Toast.makeText(context, res.errors.get(0), Toast.LENGTH_LONG).show();
                    }

                    lastCreatedFiles.clear();
                    lastCreatedFiles.addAll(res.createdFileObjects);

                    if (!lastCreatedFiles.isEmpty()) {
                        openCreatedBtn.setVisibility(View.VISIBLE);
                        openCreatedBtn.setText(context.getString(R.string.tree_creator_open_created, lastCreatedFiles.size()));
                    }

                    if (callback != null) {
                        callback.onStructureCreated(targetDir, lastCreatedFiles, false);
                    }
                });
            });
        });

        openCreatedBtn.setOnClickListener(v -> {
            if (!lastCreatedFiles.isEmpty()) {
                dialog.dismiss();
                if (callback != null) {
                    callback.onStructureCreated(targetDir, new ArrayList<>(lastCreatedFiles), true);
                }
            }
        });

        // Pre-fill from clipboard automatically if clipboard looks like a tree
        autoPreviewIfClipboardLooksLikeTree(context, inputEdit);
        dialog.show();
    }

    private static void autoPreviewIfClipboardLooksLikeTree(@NonNull Context context, @NonNull EditText inputEdit) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        if (text == null) return;
        String s = text.toString();
        if (s.contains("├──") || s.contains("└──") || s.contains("|--") || s.contains("+--") || s.contains("\\--")) {
            inputEdit.setText(s);
        }
    }

    private static void updateSummary(@NonNull Context context, @NonNull TextView summaryView,
                                      @NonNull List<TreeStructureParser.TreeEntry> entries) {
        int dirs = 0;
        int files = 0;
        int existing = 0;
        for (TreeStructureParser.TreeEntry entry : entries) {
            if (entry.isDirectory) dirs++;
            else files++;
            if (entry.alreadyExists) existing++;
        }
        if (existing > 0) {
            summaryView.setText(context.getString(R.string.tree_creator_preview_summary_existing, dirs, files, existing));
        } else {
            summaryView.setText(context.getString(R.string.tree_creator_preview_summary, dirs, files));
        }
    }

    private static final class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.VH> {
        private final List<TreeStructureParser.TreeEntry> mItems;
        private final Runnable mOnToggle;

        PreviewAdapter(List<TreeStructureParser.TreeEntry> items, Runnable onToggle) {
            mItems = items;
            mOnToggle = onToggle;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tree_preview, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TreeStructureParser.TreeEntry entry = mItems.get(position);
            int indentPx = (int) (entry.depth * 16 * holder.itemView.getResources().getDisplayMetrics().density);
            holder.itemView.setPaddingRelative(indentPx + 12, 4, 12, 4);

            if (entry.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_workspace_folder);
                holder.icon.setColorFilter(Color.parseColor("#F59E0B"));
                holder.name.setText(entry.displayName + "/");
                holder.name.setTextColor(Color.parseColor("#FDE68A"));
            } else {
                holder.icon.setImageResource(R.drawable.ic_workspace_file);
                holder.icon.setColorFilter(Color.parseColor("#38BDF8"));
                holder.name.setText(entry.displayName);
                holder.name.setTextColor(Color.parseColor("#E6EDF3"));
            }

            if (entry.alreadyExists) {
                holder.badge.setText("đã có");
                holder.badge.setTextColor(Color.parseColor("#6B7280"));
            } else {
                holder.badge.setText(entry.isDirectory ? "DIR" : "FILE");
                holder.badge.setTextColor(entry.isDirectory ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
            }

            holder.itemView.setOnClickListener(v -> {
                entry.isDirectory = !entry.isDirectory;
                notifyItemChanged(holder.getAdapterPosition());
                mOnToggle.run();
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView badge;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.tree_preview_icon);
                name = itemView.findViewById(R.id.tree_preview_name);
                badge = itemView.findViewById(R.id.tree_preview_badge);
            }
        }
    }
}

package com.termux.app.workspace;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pydroid-style multi-tab batch code and text editor activity.
 * Allows tapping across multiple open files, batch Find & Replace across all tabs,
 * Save / Save All, synchronized line numbers, syntax highlighting, undo/redo,
 * quick coding symbol bar, and running scripts directly in the Termux terminal.
 */
public class WorkspaceEditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILES = "workspace_extra_files";
    public static final String EXTRA_BASE_DIR = "workspace_extra_base_dir";
    public static final String EXTRA_RUN_COMMAND = "workspace_result_run_command";

    private static final int TOOL_FIND = 201;
    private static final int TOOL_FIND_NEXT = 202;
    private static final int TOOL_FIND_PREVIOUS = 203;
    private static final int TOOL_GO_TO_LINE = 204;
    private static final int TOOL_DUPLICATE = 205;
    private static final int TOOL_INDENT = 206;
    private static final int TOOL_UNINDENT = 207;
    private static final int TOOL_COMMENT = 208;
    private static final int TOOL_LINE_NUMBERS = 209;
    private static final int TOOL_STATISTICS = 210;
    private static final int TOOL_NANO_HELP = 211;

    private String mLastFindQuery = "";

    private static final String[] QUICK_SYMBOLS = new String[] {
        "TAB", "()", "{}", "[]", ":", ";", "=", "\"", "'", "#", "/", "_", "<", ">", ".", ","
    };

    private TextView mTitleView;
    private TextView mSubtitleView;
    private MaterialButton mSaveAllBtn;
    private ImageButton mSaveBtn;
    private ImageButton mRunBtn;
    private RecyclerView mTabsRecycler;
    private ScrollView mEditorScroll;
    private TextView mLineNumbersView;
    private EditText mCodeInput;
    private View mEmptyState;
    private LinearLayout mSymbolBar;

    private TabsAdapter mTabsAdapter;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private Runnable mHighlightRunnable;
    private boolean mSuppressTextWatcher = false;
    private String mLastSnapshotForUndo = "";
    private File mBaseDir;

    public static Intent createIntent(@NonNull Context context, @NonNull List<File> files, @NonNull File baseDir) {
        Intent intent = new Intent(context, WorkspaceEditorActivity.class);
        ArrayList<String> paths = new ArrayList<>();
        for (File f : files) {
            if (f != null) paths.add(f.getAbsolutePath());
        }
        intent.putStringArrayListExtra(EXTRA_FILES, paths);
        intent.putExtra(EXTRA_BASE_DIR, baseDir.getAbsolutePath());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_editor);

        mTitleView = findViewById(R.id.editor_title_text);
        mSubtitleView = findViewById(R.id.editor_subtitle_text);
        mSaveAllBtn = findViewById(R.id.btn_editor_save_all);
        mSaveBtn = findViewById(R.id.btn_editor_save);
        mRunBtn = findViewById(R.id.btn_editor_run);
        mTabsRecycler = findViewById(R.id.editor_tabs_recycler);
        mEditorScroll = findViewById(R.id.editor_scroll_view);
        mLineNumbersView = findViewById(R.id.editor_line_numbers);
        mCodeInput = findViewById(R.id.editor_code_input);
        mEmptyState = findViewById(R.id.editor_empty_state);
        mSymbolBar = findViewById(R.id.editor_symbol_bar);

        findViewById(R.id.btn_editor_back).setOnClickListener(v -> handleExitRequest());
        findViewById(R.id.btn_editor_add_file).setOnClickListener(v -> openFileBrowserToAddTabs());
        findViewById(R.id.btn_editor_find).setOnClickListener(v -> showFindAndReplaceDialog());
        findViewById(R.id.btn_editor_undo).setOnClickListener(v -> performUndo());
        findViewById(R.id.btn_editor_redo).setOnClickListener(v -> performRedo());
        findViewById(R.id.btn_editor_nano_tools).setOnClickListener(this::showNanoToolsMenu);

        mSaveBtn.setOnClickListener(v -> saveActiveTab());
        mSaveAllBtn.setOnClickListener(v -> saveAllTabs());
        mRunBtn.setOnClickListener(v -> runActiveFileInTerminal());

        mTabsAdapter = new TabsAdapter();
        mTabsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mTabsRecycler.setAdapter(mTabsAdapter);

        setupSymbolBar();
        setupCodeWatcher();
        handleIncomingIntent(getIntent());
        renderActiveTab();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
        renderActiveTab();
    }

    private void handleIncomingIntent(@Nullable Intent intent) {
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        String basePath = intent != null ? intent.getStringExtra(EXTRA_BASE_DIR) : null;
        if (basePath != null) {
            mBaseDir = new File(basePath);
            store.setCurrentDirectory(mBaseDir);
        } else if (store.getCurrentDirectory() != null) {
            mBaseDir = store.getCurrentDirectory();
        } else {
            mBaseDir = TermuxConstants.TERMUX_HOME_DIR;
        }

        if (intent != null) {
            ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_FILES);
            if (paths != null && !paths.isEmpty()) {
                List<File> files = new ArrayList<>();
                for (String p : paths) {
                    files.add(new File(p));
                }
                List<String> failed = store.openFilesBatch(files);
                if (!failed.isEmpty()) {
                    Toast.makeText(this, "Không thể mở: " + String.join(", ", failed), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void setupSymbolBar() {
        mSymbolBar.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (String symbol : QUICK_SYMBOLS) {
            TextView btn = new TextView(this);
            btn.setText(symbol);
            btn.setTextColor(Color.parseColor("#E6EDF3"));
            btn.setTextSize(13);
            btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_workspace_card);
            btn.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * density));
            lp.setMargins((int) (3 * density), 0, (int) (3 * density), 0);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> insertQuickSymbol(symbol));
            mSymbolBar.addView(btn);
        }
    }

    private void insertQuickSymbol(@NonNull String symbol) {
        if (WorkspaceSessionStore.getInstance().getActiveTab() == null) return;
        Editable editable = mCodeInput.getText();
        if (editable == null) return;
        int start = Math.max(0, mCodeInput.getSelectionStart());
        int end = Math.max(0, mCodeInput.getSelectionEnd());

        String toInsert;
        int cursorOffset;
        switch (symbol) {
            case "TAB":
                toInsert = "    ";
                cursorOffset = 4;
                break;
            case "()":
                toInsert = "()";
                cursorOffset = 1;
                break;
            case "{}":
                toInsert = "{}";
                cursorOffset = 1;
                break;
            case "[]":
                toInsert = "[]";
                cursorOffset = 1;
                break;
            default:
                toInsert = symbol;
                cursorOffset = symbol.length();
                break;
        }

        editable.replace(Math.min(start, end), Math.max(start, end), toInsert);
        int newPos = Math.min(start, end) + cursorOffset;
        if (newPos <= editable.length()) {
            mCodeInput.setSelection(newPos);
        }
    }

    private void setupCodeWatcher() {
        mCodeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mSuppressTextWatcher) return;
                WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
                if (tab == null) return;

                String newText = s.toString();
                if (!newText.equals(mLastSnapshotForUndo)) {
                    tab.recordUndoSnapshot(mLastSnapshotForUndo);
                    mLastSnapshotForUndo = newText;
                }
                tab.content = newText;
                updateLineNumbers(newText);
                updateHeaderAndSaveButtons();
                mTabsAdapter.notifyDataSetChanged();
                scheduleSyntaxHighlight();
            }
        });
    }

    private void scheduleSyntaxHighlight() {
        if (mHighlightRunnable != null) {
            mUiHandler.removeCallbacks(mHighlightRunnable);
        }
        mHighlightRunnable = () -> {
            WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
            if (tab == null || mCodeInput.getText() == null) return;
            mSuppressTextWatcher = true;
            int selStart = mCodeInput.getSelectionStart();
            int selEnd = mCodeInput.getSelectionEnd();
            CodeSyntaxHighlighter.apply(mCodeInput.getText(), tab.file.getName());
            if (selStart >= 0 && selEnd >= 0 && selEnd <= mCodeInput.length()) {
                mCodeInput.setSelection(selStart, selEnd);
            }
            mSuppressTextWatcher = false;
        };
        mUiHandler.postDelayed(mHighlightRunnable, 120);
    }

    private void saveCurrentEditorStateToTab() {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null) return;
        tab.content = mCodeInput.getText() != null ? mCodeInput.getText().toString() : "";
        tab.cursorStart = Math.max(0, mCodeInput.getSelectionStart());
        tab.cursorEnd = Math.max(0, mCodeInput.getSelectionEnd());
        tab.scrollY = mEditorScroll.getScrollY();
    }

    private void renderActiveTab() {
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        WorkspaceSessionStore.OpenFileTab tab = store.getActiveTab();
        mTabsAdapter.notifyDataSetChanged();

        if (tab == null) {
            mEditorScroll.setVisibility(View.GONE);
            mEmptyState.setVisibility(View.VISIBLE);
            mTitleView.setText(R.string.editor_title);
            mSubtitleView.setText(mBaseDir != null ? mBaseDir.getAbsolutePath() : "");
            updateHeaderAndSaveButtons();
            return;
        }

        mEditorScroll.setVisibility(View.VISIBLE);
        mEmptyState.setVisibility(View.GONE);

        mSuppressTextWatcher = true;
        mCodeInput.setText(tab.content);
        mLastSnapshotForUndo = tab.content;
        CodeSyntaxHighlighter.apply(mCodeInput.getText(), tab.file.getName());

        int maxLen = mCodeInput.length();
        int start = Math.min(Math.max(0, tab.cursorStart), maxLen);
        int end = Math.min(Math.max(0, tab.cursorEnd), maxLen);
        mCodeInput.setSelection(start, end);
        mSuppressTextWatcher = false;

        updateLineNumbers(tab.content);
        mEditorScroll.post(() -> mEditorScroll.scrollTo(0, tab.scrollY));
        updateHeaderAndSaveButtons();

        int activeIdx = store.getActiveTabIndex();
        if (activeIdx >= 0) {
            mTabsRecycler.smoothScrollToPosition(activeIdx);
        }
    }

    private void updateLineNumbers(@NonNull String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) {
            sb.append(i);
            if (i < lines) sb.append('\n');
        }
        mLineNumbersView.setText(sb.toString());
    }

    private void updateHeaderAndSaveButtons() {
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        WorkspaceSessionStore.OpenFileTab tab = store.getActiveTab();
        if (tab != null) {
            mTitleView.setText(tab.file.getName() + (tab.isDirty() ? " •" : ""));
            mSubtitleView.setText(tab.file.getParent());
            mSaveBtn.setEnabled(tab.isDirty());
            mSaveBtn.setAlpha(tab.isDirty() ? 1.0f : 0.45f);
            mRunBtn.setVisibility(buildRunCommandForFile(tab.file) != null ? View.VISIBLE : View.GONE);
        } else {
            mSaveBtn.setEnabled(false);
            mSaveBtn.setAlpha(0.45f);
            mRunBtn.setVisibility(View.GONE);
        }

        int dirtyCount = store.getDirtyCount();
        mSaveAllBtn.setText(getString(R.string.editor_save_all, dirtyCount));
        mSaveAllBtn.setEnabled(dirtyCount > 0);
        mSaveAllBtn.setAlpha(dirtyCount > 0 ? 1.0f : 0.55f);
    }

    private void performUndo() {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null || tab.undoStack.isEmpty()) return;
        String current = mCodeInput.getText() != null ? mCodeInput.getText().toString() : "";
        tab.redoStack.push(current);
        String prev = tab.undoStack.pop();
        applyTextFromHistory(tab, prev);
    }

    private void performRedo() {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null || tab.redoStack.isEmpty()) return;
        String current = mCodeInput.getText() != null ? mCodeInput.getText().toString() : "";
        tab.undoStack.push(current);
        String next = tab.redoStack.pop();
        applyTextFromHistory(tab, next);
    }

    private void applyTextFromHistory(@NonNull WorkspaceSessionStore.OpenFileTab tab, @NonNull String newText) {
        mSuppressTextWatcher = true;
        tab.content = newText;
        mLastSnapshotForUndo = newText;
        mCodeInput.setText(newText);
        CodeSyntaxHighlighter.apply(mCodeInput.getText(), tab.file.getName());
        mCodeInput.setSelection(Math.min(newText.length(), mCodeInput.length()));
        mSuppressTextWatcher = false;
        updateLineNumbers(newText);
        updateHeaderAndSaveButtons();
        mTabsAdapter.notifyDataSetChanged();
    }

    private void saveActiveTab() {
        saveCurrentEditorStateToTab();
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        WorkspaceSessionStore.OpenFileTab tab = store.getActiveTab();
        if (tab == null) return;
        try {
            store.saveTab(tab);
            Toast.makeText(this, getString(R.string.editor_saved_one, tab.file.getName()), Toast.LENGTH_SHORT).show();
            updateHeaderAndSaveButtons();
            mTabsAdapter.notifyDataSetChanged();
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi lưu tệp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAllTabs() {
        saveCurrentEditorStateToTab();
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        try {
            int count = store.saveAllDirtyTabs();
            Toast.makeText(this, getString(R.string.editor_saved_all, count), Toast.LENGTH_SHORT).show();
            updateHeaderAndSaveButtons();
            mTabsAdapter.notifyDataSetChanged();
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi lưu hàng loạt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void runActiveFileInTerminal() {
        saveCurrentEditorStateToTab();
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        WorkspaceSessionStore.OpenFileTab tab = store.getActiveTab();
        if (tab == null) return;

        if (store.getDirtyCount() > 0) {
            try {
                store.saveAllDirtyTabs();
            } catch (IOException e) {
                Toast.makeText(this, "Lỗi lưu trước khi chạy: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }

        String cmd = buildRunCommandForFile(tab.file);
        if (cmd == null) {
            Toast.makeText(this, "Không có trình chạy cho định dạng này", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_RUN_COMMAND, cmd);
        setResult(RESULT_OK, result);
        finish();
    }

    @Nullable
    private static String buildRunCommandForFile(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        String quoted = "'" + file.getAbsolutePath().replace("'", "'\"'\"'") + "'";
        String parent = file.getParent();
        String cdPrefix = parent != null ? "cd '" + parent.replace("'", "'\"'\"'") + "' && " : "";
        if (name.endsWith(".py")) return cdPrefix + "python3 " + quoted;
        if (name.endsWith(".sh") || name.endsWith(".bash")) return cdPrefix + "bash " + quoted;
        if (name.endsWith(".js")) return cdPrefix + "node " + quoted;
        if (name.endsWith(".rb")) return cdPrefix + "ruby " + quoted;
        if (name.endsWith(".pl")) return cdPrefix + "perl " + quoted;
        if (name.endsWith(".lua")) return cdPrefix + "lua " + quoted;
        if (name.endsWith(".php")) return cdPrefix + "php " + quoted;
        return null;
    }

    private void openFileBrowserToAddTabs() {
        saveCurrentEditorStateToTab();
        File startDir = WorkspaceSessionStore.getInstance().getRememberedDirectory(this);
        if (startDir == null) {
            startDir = mBaseDir != null ? mBaseDir : TermuxConstants.TERMUX_HOME_DIR;
            WorkspaceSessionStore.OpenFileTab active = WorkspaceSessionStore.getInstance().getActiveTab();
            if (active != null && active.file.getParentFile() != null) {
                startDir = active.file.getParentFile();
            }
        }
        new WorkspaceMenuBottomSheet().show(this, startDir, new WorkspaceMenuBottomSheet.HostActions() {
            @Override
            public void onOpenFilesInBatchEditor(@NonNull List<File> files, @NonNull File currentDir) {
                mBaseDir = currentDir;
                WorkspaceSessionStore.getInstance().openFilesBatch(files);
                renderActiveTab();
            }

            @Override
            public void onSendCdCommandToTerminal(@NonNull File targetDir) {
                mBaseDir = targetDir;
            }

            @Override
            public void onRunCommandInTerminal(@NonNull String command) {
                ClipboardManager clipboard = (ClipboardManager) WorkspaceEditorActivity.this.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Termux package install", command));
                    Toast.makeText(WorkspaceEditorActivity.this, "Đã sao chép lệnh. Mở Terminal và dán để cài package.", Toast.LENGTH_LONG).show();
                    TermuxActivity.startTermuxActivity(this);
                } else {
                    Toast.makeText(WorkspaceEditorActivity.this, "Không thể sao chép lệnh cài đặt", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showFindAndReplaceDialog() {
        saveCurrentEditorStateToTab();
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        List<WorkspaceSessionStore.OpenFileTab> tabs = store.getTabs();
        if (tabs.isEmpty()) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_find_replace, null);
        EditText findInput = view.findViewById(R.id.dialog_find_input);
        EditText replaceInput = view.findViewById(R.id.dialog_replace_input);

        new AlertDialog.Builder(this)
            .setTitle(R.string.editor_find_replace)
            .setView(view)
            .setPositiveButton(getString(R.string.editor_replace_all_tabs, tabs.size()), (d, w) -> {
                String find = findInput.getText().toString();
                String replace = replaceInput.getText().toString();
                if (find.isEmpty()) return;
                int totalReplaced = 0;
                for (WorkspaceSessionStore.OpenFileTab t : tabs) {
                    int count = countOccurrences(t.content, find);
                    if (count > 0) {
                        t.recordUndoSnapshot(t.content);
                        t.content = t.content.replace(find, replace);
                        totalReplaced += count;
                    }
                }
                renderActiveTab();
                Toast.makeText(this, getString(R.string.editor_replaced_count, totalReplaced), Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.editor_replace_current, (d, w) -> {
                String find = findInput.getText().toString();
                String replace = replaceInput.getText().toString();
                WorkspaceSessionStore.OpenFileTab active = store.getActiveTab();
                if (find.isEmpty() || active == null) return;
                int count = countOccurrences(active.content, find);
                if (count > 0) {
                    active.recordUndoSnapshot(active.content);
                    active.content = active.content.replace(find, replace);
                    renderActiveTab();
                }
                Toast.makeText(this, getString(R.string.editor_replaced_count, count), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private static int countOccurrences(@NonNull String text, @NonNull String sub) {
        if (sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private void showNanoToolsMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, TOOL_FIND, 0, "Tìm trong tệp…");
        popup.getMenu().add(0, TOOL_FIND_NEXT, 1, "Tìm tiếp");
        popup.getMenu().add(0, TOOL_FIND_PREVIOUS, 2, "Tìm ngược");
        popup.getMenu().add(0, TOOL_GO_TO_LINE, 3, "Đi tới dòng…");
        popup.getMenu().add(0, TOOL_DUPLICATE, 4, "Nhân đôi dòng / vùng chọn");
        popup.getMenu().add(0, TOOL_INDENT, 5, "Thụt lề (4 dấu cách)");
        popup.getMenu().add(0, TOOL_UNINDENT, 6, "Bỏ thụt lề");
        popup.getMenu().add(0, TOOL_COMMENT, 7, "Comment / Uncomment");
        popup.getMenu().add(0, TOOL_LINE_NUMBERS, 8,
            mLineNumbersView.getVisibility() == View.VISIBLE ? "Ẩn số dòng" : "Hiện số dòng");
        popup.getMenu().add(0, TOOL_STATISTICS, 9, "Vị trí con trỏ & thống kê");
        popup.getMenu().add(0, TOOL_NANO_HELP, 10, "Hướng dẫn sử dụng Nano");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case TOOL_FIND:
                    showFindNextDialog(false);
                    return true;
                case TOOL_FIND_NEXT:
                    searchNextOccurrence(false);
                    return true;
                case TOOL_FIND_PREVIOUS:
                    searchNextOccurrence(true);
                    return true;
                case TOOL_GO_TO_LINE:
                    showGoToLineDialog();
                    return true;
                case TOOL_DUPLICATE:
                    duplicateCurrentLines();
                    return true;
                case TOOL_INDENT:
                    transformCurrentLines(false);
                    return true;
                case TOOL_UNINDENT:
                    transformCurrentLines(true);
                    return true;
                case TOOL_COMMENT:
                    toggleCurrentLineComments();
                    return true;
                case TOOL_LINE_NUMBERS:
                    boolean visible = mLineNumbersView.getVisibility() == View.VISIBLE;
                    mLineNumbersView.setVisibility(visible ? View.GONE : View.VISIBLE);
                    return true;
                case TOOL_STATISTICS:
                    showEditorStatistics();
                    return true;
                case TOOL_NANO_HELP:
                    showNanoHelpDialog();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void showFindNextDialog(boolean backwards) {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nhập từ khóa cần tìm");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (mCodeInput.getSelectionStart() >= 0 &&
            mCodeInput.getSelectionEnd() > mCodeInput.getSelectionStart()) {
            String selected = mCodeInput.getText().subSequence(
                mCodeInput.getSelectionStart(), mCodeInput.getSelectionEnd()).toString();
            if (!selected.contains("\n") && selected.length() <= 120) {
                input.setText(selected);
            } else {
                input.setText(mLastFindQuery);
            }
        } else {
            input.setText(mLastFindQuery);
        }
        input.setSelection(input.length());

        new AlertDialog.Builder(this)
            .setTitle(backwards ? "Tìm ngược trong tệp" : "Tìm trong tệp hiện tại")
            .setView(input)
            .setPositiveButton("Tìm", (dialog, which) -> {
                mLastFindQuery = input.getText().toString();
                searchNextOccurrence(backwards);
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void searchNextOccurrence(boolean backwards) {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null) return;
        if (mLastFindQuery == null || mLastFindQuery.isEmpty()) {
            showFindNextDialog(backwards);
            return;
        }

        int selectionStart = Math.max(0, mCodeInput.getSelectionStart());
        int selectionEnd = Math.max(selectionStart, mCodeInput.getSelectionEnd());
        int from = backwards ? selectionStart : selectionEnd;
        int match = WorkspaceEditorTextActions.findNext(
            mCodeInput.getText().toString(), mLastFindQuery, from, backwards);
        if (match < 0) {
            Toast.makeText(this, "Không tìm thấy: " + mLastFindQuery, Toast.LENGTH_SHORT).show();
            return;
        }

        mCodeInput.requestFocus();
        mCodeInput.setSelection(match, Math.min(mCodeInput.length(), match + mLastFindQuery.length()));
        scrollEditorToOffset(match);
    }

    private void showGoToLineDialog() {
        if (WorkspaceSessionStore.getInstance().getActiveTab() == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Số dòng, bắt đầu từ 1");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int currentLine = WorkspaceEditorTextActions.getLineNumber(
            mCodeInput.getText().toString(), Math.max(0, mCodeInput.getSelectionStart()));
        input.setText(String.valueOf(currentLine));
        input.setSelection(input.length());

        new AlertDialog.Builder(this)
            .setTitle("Đi tới dòng")
            .setView(input)
            .setPositiveButton("Đi tới", (dialog, which) -> {
                try {
                    int line = Integer.parseInt(input.getText().toString().trim());
                    int offset = WorkspaceEditorTextActions.lineColumnToOffset(
                        mCodeInput.getText().toString(), line, 1);
                    mCodeInput.requestFocus();
                    mCodeInput.setSelection(offset);
                    scrollEditorToOffset(offset);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Số dòng không hợp lệ", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private void scrollEditorToOffset(int offset) {
        mCodeInput.post(() -> {
            android.text.Layout layout = mCodeInput.getLayout();
            if (layout == null) return;
            int safeOffset = Math.max(0, Math.min(offset, mCodeInput.length()));
            int line = layout.getLineForOffset(safeOffset);
            mEditorScroll.smoothScrollTo(0, Math.max(0, layout.getLineTop(line) - 24));
        });
    }

    private void duplicateCurrentLines() {
        if (WorkspaceSessionStore.getInstance().getActiveTab() == null) return;
        WorkspaceEditorTextActions.EditResult result =
            WorkspaceEditorTextActions.duplicateLines(
                mCodeInput.getText().toString(),
                Math.max(0, mCodeInput.getSelectionStart()),
                Math.max(0, mCodeInput.getSelectionEnd()));
        applyEditorEdit(result);
    }

    private void transformCurrentLines(boolean removeIndent) {
        if (WorkspaceSessionStore.getInstance().getActiveTab() == null) return;
        WorkspaceEditorTextActions.EditResult result =
            WorkspaceEditorTextActions.indentLines(
                mCodeInput.getText().toString(),
                Math.max(0, mCodeInput.getSelectionStart()),
                Math.max(0, mCodeInput.getSelectionEnd()),
                removeIndent);
        applyEditorEdit(result);
    }

    private void toggleCurrentLineComments() {
        WorkspaceSessionStore.OpenFileTab tab = WorkspaceSessionStore.getInstance().getActiveTab();
        if (tab == null) return;
        String marker = getLineCommentMarker(tab.file.getName());
        if (marker == null) {
            Toast.makeText(this, "Định dạng tệp này chưa có quy tắc comment dòng", Toast.LENGTH_SHORT).show();
            return;
        }
        WorkspaceEditorTextActions.EditResult result =
            WorkspaceEditorTextActions.toggleLineComments(
                mCodeInput.getText().toString(),
                Math.max(0, mCodeInput.getSelectionStart()),
                Math.max(0, mCodeInput.getSelectionEnd()),
                marker);
        applyEditorEdit(result);
    }

    @Nullable
    private static String getLineCommentMarker(@NonNull String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".py") || name.endsWith(".pyw") ||
            name.endsWith(".sh") || name.endsWith(".bash") ||
            name.endsWith(".zsh") || name.endsWith(".fish") ||
            name.endsWith(".rb") || name.endsWith(".pl") ||
            name.endsWith(".yaml") || name.endsWith(".yml") ||
            name.endsWith(".toml") || name.endsWith(".ini") ||
            name.endsWith(".conf") || name.endsWith(".properties") ||
            name.endsWith(".env") || name.endsWith(".r")) {
            return "#";
        }
        if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") ||
            name.endsWith(".js") || name.endsWith(".jsx") ||
            name.endsWith(".ts") || name.endsWith(".tsx") ||
            name.endsWith(".c") || name.endsWith(".h") ||
            name.endsWith(".cpp") || name.endsWith(".hpp") ||
            name.endsWith(".cs") || name.endsWith(".go") ||
            name.endsWith(".rs") || name.endsWith(".swift")) {
            return "//";
        }
        if (name.endsWith(".sql") || name.endsWith(".lua") || name.endsWith(".hs")) {
            return "--";
        }
        return null;
    }

    private void applyEditorEdit(@NonNull WorkspaceEditorTextActions.EditResult result) {
        if (result.text.equals(mCodeInput.getText().toString())) return;
        mCodeInput.setText(result.text);
        int start = Math.max(0, Math.min(result.selectionStart, mCodeInput.length()));
        int end = Math.max(start, Math.min(result.selectionEnd, mCodeInput.length()));
        mCodeInput.setSelection(start, end);
        mCodeInput.requestFocus();
    }

    private void showEditorStatistics() {
        String text = mCodeInput.getText() != null ? mCodeInput.getText().toString() : "";
        int cursor = Math.max(0, mCodeInput.getSelectionStart());
        int line = WorkspaceEditorTextActions.getLineNumber(text, cursor);
        int column = WorkspaceEditorTextActions.getColumnNumber(text, cursor);
        int lines = WorkspaceEditorTextActions.getLineCount(text);
        int words = WorkspaceEditorTextActions.getWordCount(text);
        String message = "Dòng: " + line + " / " + lines +
            "\nCột: " + column +
            "\nTừ: " + words +
            "\nKý tự: " + text.length();
        new AlertDialog.Builder(this)
            .setTitle("Vị trí & thống kê văn bản")
            .setMessage(message)
            .setPositiveButton("Đóng", null)
            .show();
    }

    private void showNanoHelpDialog() {
        String help =
            "GNU Nano 9.3 – hướng dẫn nhanh\n\n" +
            "Mở tệp trong Termux:\n" +
            "  pkg install nano\n" +
            "  nano ten_tep.py\n" +
            "  nano +25 ten_tep.py   (mở tại dòng 25)\n\n" +
            "Các phím tắt Nano:\n" +
            "  Ctrl+S   Lưu buffer\n" +
            "  Ctrl+O   Ghi tệp / Save As\n" +
            "  Ctrl+R   Chèn nội dung từ tệp khác\n" +
            "  Ctrl+X   Thoát Nano\n" +
            "  Ctrl+F   Tìm về phía trước (Nano 8+)\n" +
            "  Ctrl+B   Tìm về phía sau (Nano 8+)\n" +
            "  Alt+R    Tìm và thay thế\n" +
            "  Alt+F    Tìm kết quả tiếp theo\n" +
            "  Ctrl+K   Cắt dòng hoặc vùng đã đánh dấu\n" +
            "  Alt+6    Sao chép dòng/vùng\n" +
            "  Ctrl+U   Dán cutbuffer\n" +
            "  Alt+U    Undo     Alt+E: Redo\n" +
            "  Alt+G    Đi tới dòng\n" +
            "  Alt+3    Comment / Uncomment\n" +
            "  Ctrl+C   Hiện vị trí con trỏ\n" +
            "  Ctrl+G   Mở trợ giúp trong Nano\n\n" +
            "Trong editor Hs, các thao tác tương ứng nằm trong menu Nano ở thanh dưới. " +
            "Đây là trình soạn thảo Android riêng, không phải giao diện GNU Nano terminal; các phím tắt trên dùng khi chạy lệnh nano trong Termux.\n\n" +
            "Tài liệu và phím tắt được duy trì tại nano-editor.org.";
        TextView body = new TextView(this);
        body.setText(help);
        body.setTextColor(Color.parseColor("#E6EDF3"));
        body.setTextSize(14);
        body.setTextIsSelectable(true);
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        body.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
            .setTitle("Hướng dẫn GNU Nano")
            .setView(scroll)
            .setPositiveButton("Phím tắt chính thức", (dialog, which) ->
                openNanoDocumentation("https://www.nano-editor.org/dist/latest/cheatsheet.html"))
            .setNeutralButton("Sổ tay đầy đủ", (dialog, which) ->
                openNanoDocumentation("https://www.nano-editor.org/dist/latest/nano.html"))
            .setNegativeButton("Đóng", null)
            .show();
    }

    private void openNanoDocumentation(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được trình duyệt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        handleExitRequest();
    }

    private void handleExitRequest() {
        saveCurrentEditorStateToTab();
        WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
        int dirty = store.getDirtyCount();
        if (dirty == 0) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.editor_unsaved_title)
            .setMessage(getString(R.string.editor_unsaved_message, dirty))
            .setPositiveButton(R.string.editor_save_and_exit, (d, w) -> {
                try {
                    store.saveAllDirtyTabs();
                    finish();
                } catch (IOException e) {
                    Toast.makeText(this, "Lỗi khi lưu: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton(R.string.editor_discard, (d, w) -> finish())
            .setNegativeButton(R.string.editor_cancel, null)
            .show();
    }

    private final class TabsAdapter extends RecyclerView.Adapter<TabsAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_editor_tab, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            WorkspaceSessionStore store = WorkspaceSessionStore.getInstance();
            List<WorkspaceSessionStore.OpenFileTab> tabs = store.getTabs();
            WorkspaceSessionStore.OpenFileTab tab = tabs.get(position);
            boolean isActive = (position == store.getActiveTabIndex());

            holder.container.setBackgroundResource(isActive ? R.drawable.bg_editor_tab_active : R.drawable.bg_editor_tab_inactive);
            holder.title.setText(tab.file.getName());
            holder.title.setTextColor(Color.parseColor(isActive ? "#F0F6FC" : "#9CA3AF"));
            holder.dirtyDot.setVisibility(tab.isDirty() ? View.VISIBLE : View.GONE);

            holder.container.setOnClickListener(v -> {
                saveCurrentEditorStateToTab();
                store.setActiveTabIndex(holder.getAdapterPosition());
                renderActiveTab();
            });

            holder.container.setOnLongClickListener(v -> {
                PopupMenu popup = new PopupMenu(WorkspaceEditorActivity.this, v);
                popup.getMenu().add(0, 1, 0, "Lưu tab này");
                popup.getMenu().add(0, 2, 1, "Đóng các tab khác");
                popup.setOnMenuItemClickListener(item -> {
                    int idx = holder.getAdapterPosition();
                    if (idx < 0) return false;
                    if (item.getItemId() == 1) {
                        saveCurrentEditorStateToTab();
                        try {
                            store.saveTab(store.getTabs().get(idx));
                            notifyDataSetChanged();
                            updateHeaderAndSaveButtons();
                        } catch (IOException e) {
                            Toast.makeText(WorkspaceEditorActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    } else if (item.getItemId() == 2) {
                        saveCurrentEditorStateToTab();
                        store.closeOtherTabs(idx);
                        renderActiveTab();
                        return true;
                    }
                    return false;
                });
                popup.show();
                return true;
            });

            holder.closeBtn.setOnClickListener(v -> {
                int idx = holder.getAdapterPosition();
                if (idx < 0) return;
                saveCurrentEditorStateToTab();
                WorkspaceSessionStore.OpenFileTab target = store.getTabs().get(idx);
                if (target.isDirty()) {
                    new AlertDialog.Builder(WorkspaceEditorActivity.this)
                        .setTitle("Lưu " + target.file.getName() + "?")
                        .setPositiveButton("Lưu & đóng", (d, w) -> {
                            try {
                                store.saveTab(target);
                                store.closeTab(idx);
                                renderActiveTab();
                            } catch (IOException e) {
                                Toast.makeText(WorkspaceEditorActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton("Đóng không lưu", (d, w) -> {
                            store.closeTab(idx);
                            renderActiveTab();
                        })
                        .setNegativeButton("Hủy", null)
                        .show();
                } else {
                    store.closeTab(idx);
                    renderActiveTab();
                }
            });
        }

        @Override
        public int getItemCount() {
            return WorkspaceSessionStore.getInstance().getTabs().size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final View container;
            final View dirtyDot;
            final TextView title;
            final ImageButton closeBtn;

            VH(@NonNull View itemView) {
                super(itemView);
                container = itemView.findViewById(R.id.tab_container);
                dirtyDot = itemView.findViewById(R.id.tab_dirty_dot);
                title = itemView.findViewById(R.id.tab_title);
                closeBtn = itemView.findViewById(R.id.tab_close_btn);
            }
        }
    }
}

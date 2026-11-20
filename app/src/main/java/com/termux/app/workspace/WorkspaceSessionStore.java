package com.termux.app.workspace;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Workspace state for Pydroid-style multi-tab editing, persistent remembered directory,
 * and ZArchiver-style Copy/Cut clipboard.
 */
public final class WorkspaceSessionStore {

    public static final int MAX_EDITABLE_FILE_BYTES = 2 * 1024 * 1024; // 2 MB safety guard
    private static final String PREF_NAME = "termux_workspace_prefs";
    private static final String KEY_LAST_DIR = "last_opened_dir";

    public enum ClipboardMode {
        NONE,
        COPY,
        CUT
    }

    public static final class OpenFileTab {
        public final File file;
        public String content;
        public String savedContent;
        public int cursorStart;
        public int cursorEnd;
        public int scrollY;
        public final Deque<String> undoStack = new ArrayDeque<>();
        public final Deque<String> redoStack = new ArrayDeque<>();

        public OpenFileTab(@NonNull File file, @NonNull String initialContent) {
            this.file = file;
            this.content = initialContent;
            this.savedContent = initialContent;
        }

        public boolean isDirty() {
            return !content.equals(savedContent);
        }

        public void recordUndoSnapshot(@NonNull String previousContent) {
            if (undoStack.isEmpty() || !undoStack.peek().equals(previousContent)) {
                undoStack.push(previousContent);
                if (undoStack.size() > 60) {
                    // Trim oldest from bottom
                    List<String> tmp = new ArrayList<>(undoStack);
                    undoStack.clear();
                    for (int i = 0; i < 60; i++) {
                        undoStack.addLast(tmp.get(i));
                    }
                }
                redoStack.clear();
            }
        }
    }

    private static WorkspaceSessionStore sInstance;

    private final List<OpenFileTab> mTabs = new ArrayList<>();
    private int mActiveTabIndex = -1;
    private File mCurrentDirectory;
    private ClipboardMode mClipboardMode = ClipboardMode.NONE;
    private final List<File> mClipboardItems = new ArrayList<>();

    private WorkspaceSessionStore() {}

    @NonNull
    public static synchronized WorkspaceSessionStore getInstance() {
        if (sInstance == null) {
            sInstance = new WorkspaceSessionStore();
        }
        return sInstance;
    }

    public synchronized void setCurrentDirectory(@Nullable File directory) {
        setCurrentDirectory(null, directory);
    }

    public synchronized void setCurrentDirectory(@Nullable Context context, @Nullable File directory) {
        if (directory != null && directory.isDirectory()) {
            mCurrentDirectory = directory;
            if (context != null) {
                SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_DIR, directory.getAbsolutePath()).apply();
            }
        }
    }

    @Nullable
    public synchronized File getCurrentDirectory() {
        return mCurrentDirectory;
    }

    @Nullable
    public synchronized File getRememberedDirectory(@NonNull Context context) {
        if (mCurrentDirectory != null && mCurrentDirectory.exists() && mCurrentDirectory.isDirectory()) {
            return mCurrentDirectory;
        }
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedPath = prefs.getString(KEY_LAST_DIR, null);
        if (savedPath != null && !savedPath.isEmpty()) {
            File savedDir = new File(savedPath);
            if (savedDir.exists() && savedDir.isDirectory() && savedDir.canRead()) {
                mCurrentDirectory = savedDir;
                return savedDir;
            }
        }
        return null;
    }

    public synchronized void setClipboard(@NonNull ClipboardMode mode, @NonNull List<File> items) {
        mClipboardItems.clear();
        if (mode == ClipboardMode.NONE || items.isEmpty()) {
            mClipboardMode = ClipboardMode.NONE;
            return;
        }
        mClipboardMode = mode;
        for (File f : items) {
            if (f != null && f.exists()) {
                mClipboardItems.add(f);
            }
        }
        if (mClipboardItems.isEmpty()) {
            mClipboardMode = ClipboardMode.NONE;
        }
    }

    public synchronized void clearClipboard() {
        mClipboardMode = ClipboardMode.NONE;
        mClipboardItems.clear();
    }

    @NonNull
    public synchronized ClipboardMode getClipboardMode() {
        return mClipboardMode;
    }

    @NonNull
    public synchronized List<File> getClipboardItems() {
        return new ArrayList<>(mClipboardItems);
    }

    @NonNull
    public synchronized List<OpenFileTab> getTabs() {
        return new ArrayList<>(mTabs);
    }

    public synchronized int getActiveTabIndex() {
        return mActiveTabIndex;
    }

    public synchronized void setActiveTabIndex(int index) {
        if (index >= 0 && index < mTabs.size()) {
            mActiveTabIndex = index;
        } else if (mTabs.isEmpty()) {
            mActiveTabIndex = -1;
        }
    }

    @Nullable
    public synchronized OpenFileTab getActiveTab() {
        if (mActiveTabIndex >= 0 && mActiveTabIndex < mTabs.size()) {
            return mTabs.get(mActiveTabIndex);
        }
        return null;
    }

    public synchronized int openOrFocusFile(@NonNull File file) throws IOException {
        String targetPath = getCanonicalOrAbsolute(file);
        for (int i = 0; i < mTabs.size(); i++) {
            if (getCanonicalOrAbsolute(mTabs.get(i).file).equals(targetPath)) {
                mActiveTabIndex = i;
                return i;
            }
        }
        String text = readFileText(file);
        OpenFileTab tab = new OpenFileTab(file, text);
        mTabs.add(tab);
        mActiveTabIndex = mTabs.size() - 1;
        return mActiveTabIndex;
    }

    public synchronized List<String> openFilesBatch(@NonNull List<File> files) {
        List<String> failedFiles = new ArrayList<>();
        int firstOpenedIndex = -1;
        for (File f : files) {
            if (f == null || !f.exists() || !f.isFile()) continue;
            try {
                int idx = openOrFocusFile(f);
                if (firstOpenedIndex == -1) firstOpenedIndex = idx;
            } catch (IOException e) {
                failedFiles.add(f.getName());
            }
        }
        if (firstOpenedIndex != -1 && mTabs.size() > files.size()) {
            // Focus the first newly opened tab in batch
            mActiveTabIndex = firstOpenedIndex;
        } else if (!mTabs.isEmpty() && mActiveTabIndex < 0) {
            mActiveTabIndex = 0;
        }
        return failedFiles;
    }

    public synchronized void closeTab(int index) {
        if (index < 0 || index >= mTabs.size()) return;
        mTabs.remove(index);
        if (mTabs.isEmpty()) {
            mActiveTabIndex = -1;
        } else if (mActiveTabIndex >= mTabs.size()) {
            mActiveTabIndex = mTabs.size() - 1;
        } else if (index < mActiveTabIndex) {
            mActiveTabIndex--;
        }
    }

    public synchronized void closeOtherTabs(int keepIndex) {
        if (keepIndex < 0 || keepIndex >= mTabs.size()) return;
        OpenFileTab keep = mTabs.get(keepIndex);
        mTabs.clear();
        mTabs.add(keep);
        mActiveTabIndex = 0;
    }

    public synchronized int getDirtyCount() {
        int count = 0;
        for (OpenFileTab tab : mTabs) {
            if (tab.isDirty()) count++;
        }
        return count;
    }

    public synchronized void saveTab(@NonNull OpenFileTab tab) throws IOException {
        writeFileText(tab.file, tab.content);
        tab.savedContent = tab.content;
    }

    public synchronized int saveAllDirtyTabs() throws IOException {
        int savedCount = 0;
        for (OpenFileTab tab : mTabs) {
            if (tab.isDirty()) {
                writeFileText(tab.file, tab.content);
                tab.savedContent = tab.content;
                savedCount++;
            }
        }
        return savedCount;
    }

    @NonNull
    public static String readFileText(@NonNull File file) throws IOException {
        if (file.length() > MAX_EDITABLE_FILE_BYTES) {
            throw new IOException("File exceeds 2 MB editor limit");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    public static void writeFileText(@NonNull File file, @NonNull String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    @NonNull
    private static String getCanonicalOrAbsolute(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}

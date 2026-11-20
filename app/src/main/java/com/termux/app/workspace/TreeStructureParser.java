package com.termux.app.workspace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses multi-format directory tree diagrams (Unicode box-drawing, ASCII tree,
 * Markdown bullets, space/tab indentation, and flat slash-separated paths)
 * into a normalized list of directory and file entries.
 */
public final class TreeStructureParser {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*```.*$");
    private static final Pattern TREE_SUMMARY_PATTERN = Pattern.compile(
        "^\\s*\\d+\\s+(director(y|ies)|folders?|files?)(\\s*,\\s*\\d+\\s+(director(y|ies)|folders?|files?))*\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INLINE_COMMENT_PATTERN = Pattern.compile("\\s+(?:#|//|—|–).*$");

    private static final Set<String> EXTENSIONLESS_FILES = new HashSet<>(Arrays.asList(
        "makefile", "dockerfile", "license", "readme", "procfile", "gemfile",
        "rakefile", "vagrantfile", "cmakelists.txt", "authors", "contributors",
        "changelog", "notice", "gradlew", "mvnw"
    ));

    public static final class TreeEntry {
        public final String relativePath;
        public final String displayName;
        public final int depth;
        public boolean isDirectory;
        public boolean alreadyExists;

        public TreeEntry(@NonNull String relativePath, @NonNull String displayName, int depth, boolean isDirectory) {
            this.relativePath = relativePath;
            this.displayName = displayName;
            this.depth = depth;
            this.isDirectory = isDirectory;
        }
    }

    public static final class CreationResult {
        public int createdDirs;
        public int createdFiles;
        public int skippedExisting;
        public final List<File> createdFileObjects = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    private static final class StackNode {
        final int indentCol;
        final String path;
        final boolean isSkippedRootDot;

        StackNode(int indentCol, String path, boolean isSkippedRootDot) {
            this.indentCol = indentCol;
            this.path = path;
            this.isSkippedRootDot = isSkippedRootDot;
        }
    }

    private static final class ParsedLine {
        final int indentCol;
        final String rawName;
        final boolean explicitDir;

        ParsedLine(int indentCol, String rawName, boolean explicitDir) {
            this.indentCol = indentCol;
            this.rawName = rawName;
            this.explicitDir = explicitDir;
        }
    }

    private TreeStructureParser() {}

    @NonNull
    public static List<TreeEntry> parse(@Nullable String input, boolean includeSingleRootFolder) {
        if (input == null || input.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String[] lines = input.split("\\r?\\n");
        List<ParsedLine> parsedLines = new ArrayList<>();

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            if (CODE_FENCE_PATTERN.matcher(rawLine).matches()) continue;
            if (TREE_SUMMARY_PATTERN.matcher(rawLine).matches()) continue;

            String expanded = rawLine.replace('\t', ' ')
                .replace("\u00A0", " ");
            // Expand tabs cleanly to 4 spaces on original line instead
            StringBuilder tabExpanded = new StringBuilder();
            for (int i = 0; i < rawLine.length(); i++) {
                char c = rawLine.charAt(i);
                if (c == '\t') tabExpanded.append("    ");
                else if (c == '\u00A0') tabExpanded.append(' ');
                else tabExpanded.append(c);
            }
            expanded = tabExpanded.toString();

            if (expanded.trim().isEmpty()) continue;

            ParsedLine parsed = parseSingleLine(expanded);
            if (parsed != null && !parsed.rawName.isEmpty()) {
                parsedLines.add(parsed);
            }
        }

        if (parsedLines.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, TreeEntry> entryMap = new LinkedHashMap<>();
        Deque<StackNode> stack = new ArrayDeque<>();
        boolean skippedRootDot = false;

        for (int i = 0; i < parsedLines.size(); i++) {
            ParsedLine line = parsedLines.get(i);
            int col = line.indentCol;

            while (!stack.isEmpty() && stack.peek().indentCol >= col) {
                stack.pop();
            }

            // Check if this line is just "." or "./" at the root
            if (stack.isEmpty() && (line.rawName.equals(".") || line.rawName.equals("./"))) {
                stack.push(new StackNode(col, "", true));
                skippedRootDot = true;
                continue;
            }

            String parentPath = "";
            if (!stack.isEmpty()) {
                parentPath = stack.peek().path;
                if (!parentPath.isEmpty()) {
                    TreeEntry parentEntry = entryMap.get(parentPath);
                    if (parentEntry != null) {
                        parentEntry.isDirectory = true;
                    }
                }
            }

            // Check if the next line is deeper than this line -> implies this node is a directory
            boolean hasChildren = (i + 1 < parsedLines.size()) && (parsedLines.get(i + 1).indentCol > col);

            // Handle nested path segments inside rawName (e.g., "src/main/App.java")
            String normalizedSegment = line.rawName.replace('\\', '/');
            while (normalizedSegment.startsWith("./")) {
                normalizedSegment = normalizedSegment.substring(2);
            }
            while (normalizedSegment.startsWith("/")) {
                normalizedSegment = normalizedSegment.substring(1);
            }
            if (normalizedSegment.isEmpty()) continue;

            String[] parts = normalizedSegment.split("/");
            String currentPath = parentPath;
            for (int p = 0; p < parts.length; p++) {
                String segment = sanitizeSegment(parts[p]);
                if (segment == null) {
                    currentPath = null;
                    break;
                }
                currentPath = currentPath.isEmpty() ? segment : (currentPath + "/" + segment);
                boolean isLastPart = (p == parts.length - 1);
                boolean isDir;
                if (!isLastPart) {
                    isDir = true;
                } else {
                    isDir = line.explicitDir || hasChildren || inferIsDirectory(segment);
                }

                TreeEntry existing = entryMap.get(currentPath);
                if (existing == null) {
                    int depth = countDepth(currentPath);
                    entryMap.put(currentPath, new TreeEntry(currentPath, segment, depth, isDir));
                } else if (isDir) {
                    existing.isDirectory = true;
                }
            }

            if (currentPath != null) {
                stack.push(new StackNode(col, currentPath, false));
            }
        }

        List<TreeEntry> result = new ArrayList<>(entryMap.values());

        // Optionally strip single top-level root folder if user unchecked "Include root folder"
        if (!includeSingleRootFolder && !skippedRootDot && !result.isEmpty()) {
            String singleRoot = findSingleRootPrefix(result);
            if (singleRoot != null) {
                List<TreeEntry> stripped = new ArrayList<>();
                String prefix = singleRoot + "/";
                for (TreeEntry entry : result) {
                    if (entry.relativePath.equals(singleRoot)) continue;
                    if (entry.relativePath.startsWith(prefix)) {
                        String newRel = entry.relativePath.substring(prefix.length());
                        TreeEntry copy = new TreeEntry(newRel, entry.displayName, countDepth(newRel), entry.isDirectory);
                        stripped.add(copy);
                    }
                }
                if (!stripped.isEmpty()) {
                    result = stripped;
                }
            }
        }

        return result;
    }

    public static boolean hasSingleRootFolder(@NonNull List<TreeEntry> entries) {
        return findSingleRootPrefix(entries) != null;
    }

    @Nullable
    private static String findSingleRootPrefix(@NonNull List<TreeEntry> entries) {
        String root = null;
        int childCount = 0;
        for (TreeEntry entry : entries) {
            int slashIdx = entry.relativePath.indexOf('/');
            String top = (slashIdx >= 0) ? entry.relativePath.substring(0, slashIdx) : entry.relativePath;
            if (root == null) {
                root = top;
            } else if (!root.equals(top)) {
                return null;
            }
            if (slashIdx >= 0) {
                childCount++;
            }
        }
        return (root != null && childCount > 0) ? root : null;
    }

    private static int countDepth(@NonNull String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') depth++;
        }
        return depth;
    }

    @Nullable
    private static ParsedLine parseSingleLine(@NonNull String line) {
        int len = line.length();
        int idx = 0;

        // Step 1: Consume tree guides & indentation
        while (idx < len) {
            char c = line.charAt(idx);
            if (c == ' ' || c == '│' || c == '┃' || c == '├' || c == '┣' || c == '└' || c == '┗' || c == '─' || c == '━') {
                idx++;
                continue;
            }
            // ASCII pipe guide (e.g. "|   " or "|-- " or "|- ")
            if (c == '|') {
                idx++;
                continue;
            }
            // ASCII branch connectors at current position: "+--", "+-", "\--", "\-", "`--", "`-"
            if ((c == '+' || c == '\\' || c == '`') && idx + 1 < len && line.charAt(idx + 1) == '-') {
                idx += 2;
                while (idx < len && line.charAt(idx) == '-') {
                    idx++;
                }
                continue;
            }
            // Markdown bullet "- ", "* ", "+ " followed by whitespace
            if ((c == '-' || c == '*' || c == '+') && idx + 1 < len && line.charAt(idx + 1) == ' ') {
                idx += 2;
                continue;
            }
            break;
        }

        if (idx >= len) return null;

        int indentCol = idx;
        String rest = line.substring(idx).trim();
        if (rest.isEmpty()) return null;

        // Strip inline comments like "  # comment", "  // comment", "  — desc"
        Matcher commentMatcher = INLINE_COMMENT_PATTERN.matcher(rest);
        if (commentMatcher.find()) {
            rest = rest.substring(0, commentMatcher.start()).trim();
        }
        if (rest.isEmpty()) return null;

        // Strip surrounding quotes or backticks if pasted from markdown
        if (rest.length() >= 2 &&
            ((rest.startsWith("`") && rest.endsWith("`")) ||
             (rest.startsWith("\"") && rest.endsWith("\"")) ||
             (rest.startsWith("'") && rest.endsWith("'")))) {
            rest = rest.substring(1, rest.length() - 1).trim();
        }
        if (rest.isEmpty()) return null;

        boolean explicitDir = rest.endsWith("/") || rest.endsWith("\\");
        while (rest.endsWith("/") || rest.endsWith("\\")) {
            rest = rest.substring(0, rest.length() - 1);
        }

        if (rest.isEmpty()) return null;
        return new ParsedLine(indentCol, rest, explicitDir);
    }

    @Nullable
    private static String sanitizeSegment(@NonNull String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            return null;
        }
        if (trimmed.indexOf('\0') >= 0) {
            return null;
        }
        return trimmed;
    }

    /**
     * Heuristic for leaf nodes without trailing slash and without children:
     * Treat as file by default (standard `touch` behavior), since folders without trailing slash
     * usually have children or trailing `/`, and user can tap to toggle in preview.
     */
    private static boolean inferIsDirectory(@NonNull String name) {
        if (name.startsWith(".") || name.contains(".")) {
            return false;
        }
        String lower = name.toLowerCase();
        if (EXTENSIONLESS_FILES.contains(lower)) {
            return false;
        }
        return false;
    }

    public static void markExisting(@NonNull File baseDir, @NonNull List<TreeEntry> entries) {
        for (TreeEntry entry : entries) {
            File target = new File(baseDir, entry.relativePath);
            entry.alreadyExists = target.exists();
        }
    }

    @NonNull
    public static CreationResult createStructure(@NonNull File baseDir, @NonNull List<TreeEntry> entries) {
        CreationResult result = new CreationResult();

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            result.errors.add("Cannot create base directory: " + baseDir.getAbsolutePath());
            return result;
        }

        String baseCanonical;
        try {
            baseCanonical = baseDir.getCanonicalPath();
        } catch (IOException e) {
            baseCanonical = baseDir.getAbsolutePath();
        }

        // First pass: create all directories
        for (TreeEntry entry : entries) {
            if (!entry.isDirectory) continue;
            File target = new File(baseDir, entry.relativePath);
            if (!isInsideBase(baseCanonical, target)) {
                result.errors.add("Unsafe path skipped: " + entry.relativePath);
                continue;
            }
            if (target.exists()) {
                if (target.isDirectory()) {
                    result.skippedExisting++;
                } else {
                    result.errors.add("File exists at folder path: " + entry.relativePath);
                }
            } else if (target.mkdirs()) {
                result.createdDirs++;
            } else {
                result.errors.add("Failed to create folder: " + entry.relativePath);
            }
        }

        // Second pass: create all files
        for (TreeEntry entry : entries) {
            if (entry.isDirectory) continue;
            File target = new File(baseDir, entry.relativePath);
            if (!isInsideBase(baseCanonical, target)) {
                result.errors.add("Unsafe path skipped: " + entry.relativePath);
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                result.errors.add("Failed to create parent folder for: " + entry.relativePath);
                continue;
            }
            if (target.exists()) {
                result.skippedExisting++;
                result.createdFileObjects.add(target);
            } else {
                try {
                    if (target.createNewFile()) {
                        result.createdFiles++;
                        result.createdFileObjects.add(target);
                    } else {
                        result.errors.add("Failed to create file: " + entry.relativePath);
                    }
                } catch (IOException e) {
                    result.errors.add(entry.relativePath + ": " + e.getMessage());
                }
            }
        }

        return result;
    }

    private static boolean isInsideBase(@NonNull String baseCanonical, @NonNull File target) {
        try {
            String targetCanonical = target.getCanonicalPath();
            return targetCanonical.equals(baseCanonical) ||
                targetCanonical.startsWith(baseCanonical + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}

package com.termux.app.workspace;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure text-editing operations inspired by common GNU nano workflows.
 * Kept separate from the Android UI so the operations can be unit-tested.
 */
public final class WorkspaceEditorTextActions {

    private WorkspaceEditorTextActions() {}

    public static final class EditResult {
        public final String text;
        public final int selectionStart;
        public final int selectionEnd;

        EditResult(@NonNull String text, int selectionStart, int selectionEnd) {
            this.text = text;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }
    }

    private static final class LineRange {
        final int start;
        final int end;

        LineRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class LineChange {
        final String text;
        final int changeStart;
        final int removed;
        final int inserted;

        LineChange(String text, int changeStart, int removed, int inserted) {
            this.text = text;
            this.changeStart = changeStart;
            this.removed = removed;
            this.inserted = inserted;
        }

        int mapColumn(int column) {
            int safeColumn = Math.max(0, column);
            if (removed > 0) {
                if (safeColumn <= changeStart) return safeColumn;
                if (safeColumn < changeStart + removed) return changeStart;
                return safeColumn - removed;
            }
            if (inserted > 0 && safeColumn >= changeStart) {
                return safeColumn + inserted;
            }
            return safeColumn;
        }
    }

    public static EditResult indentLines(
        @NonNull String text, int selectionStart, int selectionEnd, boolean removeIndent
    ) {
        LineRange range = getLineRange(text, selectionStart, selectionEnd);
        String segment = text.substring(range.start, range.end);
        String[] lines = segment.split("\n", -1);
        List<LineChange> changes = new ArrayList<>();
        for (String line : lines) {
            if (!removeIndent) {
                changes.add(new LineChange("    " + line, 0, 0, 4));
            } else {
                int remove = line.startsWith("\t") ? 1 : 0;
                if (remove == 0) {
                    while (remove < 4 && remove < line.length() && line.charAt(remove) == ' ') {
                        remove++;
                    }
                }
                changes.add(new LineChange(line.substring(remove), 0, remove, 0));
            }
        }
        return applyLineChanges(text, range, changes, selectionStart, selectionEnd);
    }

    public static EditResult toggleLineComments(
        @NonNull String text, int selectionStart, int selectionEnd, @NonNull String marker
    ) {
        if (marker.isEmpty()) {
            return new EditResult(text, clamp(selectionStart, text.length()), clamp(selectionEnd, text.length()));
        }
        LineRange range = getLineRange(text, selectionStart, selectionEnd);
        String segment = text.substring(range.start, range.end);
        String[] lines = segment.split("\n", -1);

        boolean foundCode = false;
        boolean allCommented = true;
        for (String line : lines) {
            int indent = leadingWhitespace(line);
            if (indent == line.length()) continue;
            foundCode = true;
            if (!line.startsWith(marker, indent)) allCommented = false;
        }
        if (!foundCode) {
            return new EditResult(text, clamp(selectionStart, text.length()), clamp(selectionEnd, text.length()));
        }

        List<LineChange> changes = new ArrayList<>();
        for (String line : lines) {
            int indent = leadingWhitespace(line);
            if (indent == line.length()) {
                changes.add(new LineChange(line, 0, 0, 0));
            } else if (allCommented) {
                int removed = marker.length();
                if (indent + removed < line.length() && line.charAt(indent + removed) == ' ') removed++;
                changes.add(new LineChange(
                    line.substring(0, indent) + line.substring(indent + removed),
                    indent, removed, 0));
            } else {
                String prefix = marker + " ";
                changes.add(new LineChange(
                    line.substring(0, indent) + prefix + line.substring(indent),
                    indent, 0, prefix.length()));
            }
        }
        return applyLineChanges(text, range, changes, selectionStart, selectionEnd);
    }

    public static EditResult duplicateLines(@NonNull String text, int selectionStart, int selectionEnd) {
        LineRange range = getLineRange(text, selectionStart, selectionEnd);
        String segment = text.substring(range.start, range.end);
        String updated = text.substring(0, range.end) + "\n" + segment + text.substring(range.end);
        int duplicatedStart = range.end + 1;
        return new EditResult(updated, duplicatedStart, duplicatedStart + segment.length());
    }

    public static int findNext(@NonNull String text, @NonNull String query, int fromIndex, boolean backwards) {
        if (query.isEmpty() || text.isEmpty() || query.length() > text.length()) return -1;
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int from = clamp(fromIndex, text.length());
        if (backwards) {
            int hit = haystack.lastIndexOf(needle, Math.min(haystack.length() - 1, from - 1));
            return hit >= 0 ? hit : haystack.lastIndexOf(needle);
        }
        int hit = haystack.indexOf(needle, from);
        return hit >= 0 ? hit : haystack.indexOf(needle);
    }

    public static int lineColumnToOffset(@NonNull String text, int line, int column) {
        int targetLine = Math.max(1, line);
        int targetColumn = Math.max(1, column);
        int currentLine = 1;
        int lineStart = 0;
        while (currentLine < targetLine) {
            int nextBreak = text.indexOf('\n', lineStart);
            if (nextBreak < 0) return text.length();
            lineStart = nextBreak + 1;
            currentLine++;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        return Math.min(lineStart + targetColumn - 1, lineEnd);
    }

    public static int getLineNumber(@NonNull String text, int offset) {
        int safeOffset = clamp(offset, text.length());
        int line = 1;
        for (int i = 0; i < safeOffset; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    public static int getColumnNumber(@NonNull String text, int offset) {
        int safeOffset = clamp(offset, text.length());
        int previousBreak = safeOffset == 0 ? -1 : text.lastIndexOf('\n', safeOffset - 1);
        return safeOffset - previousBreak;
    }

    public static int getLineCount(@NonNull String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    public static int getWordCount(@NonNull String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }

    private static EditResult applyLineChanges(
        String text, LineRange range, List<LineChange> changes, int selectionStart, int selectionEnd
    ) {
        String originalSegment = text.substring(range.start, range.end);
        StringBuilder changedSegment = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) changedSegment.append('\n');
            changedSegment.append(changes.get(i).text);
        }
        String updated = text.substring(0, range.start) + changedSegment + text.substring(range.end);
        int relativeStart = clamp(selectionStart - range.start, originalSegment.length());
        int relativeEnd = clamp(selectionEnd - range.start, originalSegment.length());
        int mappedStart = range.start + mapOffset(originalSegment, changes, relativeStart);
        int mappedEnd = range.start + mapOffset(originalSegment, changes, relativeEnd);
        return new EditResult(updated, Math.min(mappedStart, mappedEnd), Math.max(mappedStart, mappedEnd));
    }

    private static int mapOffset(String original, List<LineChange> changes, int offset) {
        int oldLineStart = 0;
        int newLineStart = 0;
        for (int i = 0; i < changes.size(); i++) {
            int breakAt = original.indexOf('\n', oldLineStart);
            int oldLineEnd = breakAt < 0 ? original.length() : breakAt;
            String oldLine = original.substring(oldLineStart, oldLineEnd);
            LineChange change = changes.get(i);
            if (offset <= oldLineEnd || i == changes.size() - 1) {
                int column = Math.min(oldLine.length(), Math.max(0, offset - oldLineStart));
                return newLineStart + change.mapColumn(column);
            }
            oldLineStart = oldLineEnd + 1;
            newLineStart += change.text.length() + 1;
        }
        return newLineStart;
    }

    private static LineRange getLineRange(String text, int selectionStart, int selectionEnd) {
        int startOffset = clamp(Math.min(selectionStart, selectionEnd), text.length());
        int endOffset = clamp(Math.max(selectionStart, selectionEnd), text.length());
        int previousBreak = startOffset == 0 ? -1 : text.lastIndexOf('\n', startOffset - 1);
        int start = previousBreak + 1;
        int probe = endOffset > startOffset ? endOffset - 1 : startOffset;
        int nextBreak = text.indexOf('\n', probe);
        int end = nextBreak < 0 ? text.length() : nextBreak;
        return new LineRange(start, Math.max(start, end));
    }

    private static int leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) index++;
        return index;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}

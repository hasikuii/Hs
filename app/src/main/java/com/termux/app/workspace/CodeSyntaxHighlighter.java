package com.termux.app.workspace;

import android.graphics.Color;
import android.text.Editable;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight regex-based syntax highlighter for the Pydroid-style batch code editor.
 * Caps highlighting at 100 KB for instant typing responsiveness.
 */
public final class CodeSyntaxHighlighter {

    private static final int MAX_HIGHLIGHT_CHARS = 100_000;

    private static final int COLOR_KEYWORD = Color.parseColor("#C678DD");
    private static final int COLOR_STRING = Color.parseColor("#98C379");
    private static final int COLOR_COMMENT = Color.parseColor("#6B7280");
    private static final int COLOR_NUMBER = Color.parseColor("#D19A66");
    private static final int COLOR_DECORATOR = Color.parseColor("#38BDF8");

    private static final Pattern KEYWORDS_PATTERN = Pattern.compile(
        "\\b(def|class|import|from|as|return|if|elif|else|for|while|break|continue|pass|try|except|finally|raise|with|yield|lambda|async|await|True|False|None|and|or|not|in|is|" +
            "function|const|let|var|export|default|switch|case|new|this|typeof|true|false|null|undefined|" +
            "public|private|protected|static|final|void|int|long|float|double|boolean|char|byte|extends|implements|interface|package|val|fun|object|when|override|" +
            "echo|fi|then|do|done|esac|local|exit)\\b"
    );

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern STRING_PATTERN = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern COMMENT_HASH_PATTERN = Pattern.compile("#[^\\n]*");
    private static final Pattern COMMENT_SLASH_PATTERN = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern TAG_OR_KEY_PATTERN = Pattern.compile("<[^>]+>|@[A-Za-z_][A-Za-z0-9_]*");

    private CodeSyntaxHighlighter() {}

    public static void apply(@NonNull Editable editable, @NonNull String fileName) {
        if (editable.length() > MAX_HIGHLIGHT_CHARS) {
            clearSpans(editable);
            return;
        }
        clearSpans(editable);

        String text = editable.toString();
        String lower = fileName.toLowerCase();

        // Order matters: numbers -> keywords -> tags/decorators -> strings -> comments (highest precedence)
        applyPattern(editable, text, NUMBER_PATTERN, COLOR_NUMBER);
        applyPattern(editable, text, KEYWORDS_PATTERN, COLOR_KEYWORD);
        applyPattern(editable, text, TAG_OR_KEY_PATTERN, COLOR_DECORATOR);
        applyPattern(editable, text, STRING_PATTERN, COLOR_STRING);

        if (lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".bash") ||
            lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".toml") ||
            lower.endsWith(".conf") || lower.endsWith(".properties")) {
            applyPattern(editable, text, COMMENT_HASH_PATTERN, COLOR_COMMENT);
        } else {
            applyPattern(editable, text, COMMENT_SLASH_PATTERN, COLOR_COMMENT);
            if (!lower.endsWith(".java") && !lower.endsWith(".kt") && !lower.endsWith(".js") && !lower.endsWith(".ts")) {
                applyPattern(editable, text, COMMENT_HASH_PATTERN, COLOR_COMMENT);
            }
        }
    }

    private static void clearSpans(@NonNull Editable editable) {
        ForegroundColorSpan[] spans = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            editable.removeSpan(span);
        }
    }

    private static void applyPattern(@NonNull Editable editable, @NonNull String text, @NonNull Pattern pattern, int color) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            editable.setSpan(
                new ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }
}

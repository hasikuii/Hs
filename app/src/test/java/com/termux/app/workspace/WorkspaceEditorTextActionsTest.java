package com.termux.app.workspace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorkspaceEditorTextActionsTest {

    @Test
    public void indentAndUnindentCurrentLinePreservesCursorColumn() {
        WorkspaceEditorTextActions.EditResult indented =
            WorkspaceEditorTextActions.indentLines("one\ntwo", 5, 5, false);
        assertEquals("one\n    two", indented.text);
        assertEquals(9, indented.selectionStart);

        WorkspaceEditorTextActions.EditResult unindented =
            WorkspaceEditorTextActions.indentLines(indented.text, 9, 9, true);
        assertEquals("one\ntwo", unindented.text);
        assertEquals(5, unindented.selectionStart);
    }

    @Test
    public void commentsToggleForSelectedLines() {
        WorkspaceEditorTextActions.EditResult commented =
            WorkspaceEditorTextActions.toggleLineComments("  print(1)\n  print(2)", 2, 20, "#");
        assertEquals("  # print(1)\n  # print(2)", commented.text);

        WorkspaceEditorTextActions.EditResult uncommented =
            WorkspaceEditorTextActions.toggleLineComments(commented.text, 2, 22, "#");
        assertEquals("  print(1)\n  print(2)", uncommented.text);
    }

    @Test
    public void duplicateSelectedLineAddsCopyImmediatelyAfter() {
        WorkspaceEditorTextActions.EditResult result =
            WorkspaceEditorTextActions.duplicateLines("alpha\nbeta", 0, 5);
        assertEquals("alpha\nalpha\nbeta", result.text);
        assertEquals(6, result.selectionStart);
        assertEquals(11, result.selectionEnd);
    }

    @Test
    public void lineAndColumnNavigationClampsToExistingLine() {
        assertEquals(6, WorkspaceEditorTextActions.lineColumnToOffset("abc\ndef", 2, 3));
        assertEquals(7, WorkspaceEditorTextActions.lineColumnToOffset("abc\ndef", 9, 1));
        assertEquals(2, WorkspaceEditorTextActions.getLineNumber("abc\ndef", 5));
        assertEquals(2, WorkspaceEditorTextActions.getColumnNumber("abc\ndef", 5));
    }

    @Test
    public void forwardAndBackwardSearchWrapAndIgnoreCase() {
        assertEquals(0, WorkspaceEditorTextActions.findNext("Alpha beta ALPHA", "alpha", 12, false));
        assertEquals(0, WorkspaceEditorTextActions.findNext("Alpha beta ALPHA", "alpha", 11, true));
    }
}

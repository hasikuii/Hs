package com.termux.app.workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkspaceMoveHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void movesFilesAndFoldersBatchToDestination() throws Exception {
        File root = tempFolder.newFolder("workspace");
        File fileA = new File(root, "main.py");
        Files.write(fileA.toPath(), "print('ok')\n".getBytes(StandardCharsets.UTF_8));

        File subDir = new File(root, "pkg");
        assertTrue(subDir.mkdirs());
        File nestedFile = new File(subDir, "helper.py");
        Files.write(nestedFile.toPath(), "x = 1\n".getBytes(StandardCharsets.UTF_8));

        File dest = new File(root, "target");
        assertTrue(dest.mkdirs());

        WorkspaceMoveHelper.MoveResult res = WorkspaceMoveHelper.moveItems(
            null, Arrays.asList(fileA, subDir), dest);

        assertEquals(2, res.movedCount);
        assertEquals(0, res.skippedCount);
        assertFalse(fileA.exists());
        assertFalse(subDir.exists());
        assertTrue(new File(dest, "main.py").isFile());
        assertTrue(new File(dest, "pkg/helper.py").isFile());
    }

    @Test
    public void rejectsMovingFolderIntoItselfOrDescendant() throws Exception {
        File root = tempFolder.newFolder("cycle_test");
        File parent = new File(root, "parent");
        File child = new File(parent, "child");
        assertTrue(child.mkdirs());

        WorkspaceMoveHelper.MoveResult intoSelf = WorkspaceMoveHelper.moveItems(
            null, Collections.singletonList(parent), parent);
        assertEquals(0, intoSelf.movedCount);
        assertEquals(1, intoSelf.skippedCount);

        WorkspaceMoveHelper.MoveResult intoChild = WorkspaceMoveHelper.moveItems(
            null, Collections.singletonList(parent), child);
        assertEquals(0, intoChild.movedCount);
        assertEquals(1, intoChild.skippedCount);
        assertTrue(child.exists());
    }

    @Test
    public void skipsExistingNameConflictInDestination() throws Exception {
        File root = tempFolder.newFolder("conflict_test");
        File src = new File(root, "config.json");
        Files.write(src.toPath(), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        File dest = new File(root, "out");
        assertTrue(dest.mkdirs());
        File existing = new File(dest, "config.json");
        Files.write(existing.toPath(), "{\"a\":2}".getBytes(StandardCharsets.UTF_8));

        WorkspaceMoveHelper.MoveResult res = WorkspaceMoveHelper.moveItems(
            null, Collections.singletonList(src), dest);

        assertEquals(0, res.movedCount);
        assertEquals(1, res.skippedCount);
        assertTrue(src.exists());
        assertTrue(existing.exists());
    }
}

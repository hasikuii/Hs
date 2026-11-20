package com.termux.app.workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WorkspaceArchiveHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void copyItemsAndDuplicateWithUniqueNames() throws Exception {
        File root = tempFolder.newFolder("copy_root");
        File file = new File(root, "script.py");
        Files.write(file.toPath(), "print(1)\n".getBytes(StandardCharsets.UTF_8));

        File folder = new File(root, "mod");
        assertTrue(folder.mkdirs());
        Files.write(new File(folder, "a.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));

        File dup = WorkspaceArchiveHelper.duplicateItem(file);
        assertNotNull(dup);
        assertEquals("script_copy.py", dup.getName());
        assertEquals("print(1)\n", new String(Files.readAllBytes(dup.toPath()), StandardCharsets.UTF_8));

        File dest = tempFolder.newFolder("copy_dest");
        WorkspaceArchiveHelper.CopyResult first = WorkspaceArchiveHelper.copyItems(
            Arrays.asList(file, folder), dest, true);
        assertEquals(2, first.copiedCount);
        assertEquals(0, first.skippedCount);
        assertTrue(new File(dest, "script.py").isFile());
        assertTrue(new File(dest, "mod/a.txt").isFile());

        WorkspaceArchiveHelper.CopyResult second = WorkspaceArchiveHelper.copyItems(
            Collections.singletonList(file), dest, true);
        assertEquals(1, second.copiedCount);
        assertTrue(new File(dest, "script_copy.py").isFile());

        // Prevent copying directory into itself
        WorkspaceArchiveHelper.CopyResult selfCopy = WorkspaceArchiveHelper.copyItems(
            Collections.singletonList(folder), folder, true);
        assertEquals(0, selfCopy.copiedCount);
        assertEquals(1, selfCopy.skippedCount);
    }

    @Test
    public void compressListAndExtractZipTarGzAndTar() throws Exception {
        File srcRoot = tempFolder.newFolder("archive_src");
        File fileA = new File(srcRoot, "hello.txt");
        Files.write(fileA.toPath(), "xin chao termux".getBytes(StandardCharsets.UTF_8));

        String deepName = "very_long_nested_directory_name_exceeding_one_hundred_bytes_for_gnu_tar_longlink_testing_1234567890";
        File nestedDir = new File(srcRoot, deepName);
        assertTrue(nestedDir.mkdirs());
        File nestedFile = new File(nestedDir, "data_file_inside_nested_dir.json");
        Files.write(nestedFile.toPath(), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        File archivesDir = tempFolder.newFolder("archives_out");

        WorkspaceArchiveHelper.ArchiveFormat[] formats = new WorkspaceArchiveHelper.ArchiveFormat[] {
            WorkspaceArchiveHelper.ArchiveFormat.ZIP,
            WorkspaceArchiveHelper.ArchiveFormat.TAR_GZ,
            WorkspaceArchiveHelper.ArchiveFormat.TAR
        };

        for (WorkspaceArchiveHelper.ArchiveFormat fmt : formats) {
            File archive = new File(archivesDir, "bundle" + fmt.extension);
            WorkspaceArchiveHelper.compress(Arrays.asList(fileA, nestedDir), archive, fmt);
            assertTrue(archive.isFile());
            assertTrue(WorkspaceArchiveHelper.isArchiveFile(archive));

            List<WorkspaceArchiveHelper.ArchiveEntryInfo> entries = WorkspaceArchiveHelper.listArchiveEntries(archive);
            assertTrue(entries.size() >= 2);

            File extractDir = tempFolder.newFolder("extract_" + fmt.name());
            int extractedCount = WorkspaceArchiveHelper.extract(archive, extractDir);
            assertEquals(2, extractedCount);

            File outFileA = new File(extractDir, "hello.txt");
            File outNested = new File(extractDir, deepName + "/data_file_inside_nested_dir.json");
            assertTrue("Missing hello.txt for " + fmt, outFileA.isFile());
            assertTrue("Missing long-path file for " + fmt, outNested.isFile());
            assertEquals("xin chao termux", new String(Files.readAllBytes(outFileA.toPath()), StandardCharsets.UTF_8));
            assertEquals("{\"ok\":true}", new String(Files.readAllBytes(outNested.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void rejectsZipSlipTraversal() throws Exception {
        File dir = tempFolder.newFolder("zip_slip");
        File maliciousZip = new File(dir, "evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(maliciousZip))) {
            zos.putNextEntry(new ZipEntry("../../outside.txt"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File outDir = new File(dir, "target");
        try {
            WorkspaceArchiveHelper.extract(maliciousZip, outDir);
            fail("Expected IOException for Zip-Slip path traversal");
        } catch (IOException expected) {
            assertFalse(new File(dir, "outside.txt").exists());
        }
    }
}

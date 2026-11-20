package com.termux.app.workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TreeStructureParserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void parsesUnicodeTreeWithCommentsAndCreatesOnDisk() throws Exception {
        String tree = "```text\n" +
            "my-app/\n" +
            "├── src/\n" +
            "│   ├── main.py         # entry point\n" +
            "│   └── utils/\n" +
            "│       └── helper.py   // utility\n" +
            "├── tests/\n" +
            "│   └── test_main.py\n" +
            "└── README.md\n" +
            "```\n" +
            "3 directories, 4 files";

        List<TreeStructureParser.TreeEntry> entries = TreeStructureParser.parse(tree, true);
        assertEquals(8, entries.size());

        File base = tempFolder.newFolder("workspace");
        TreeStructureParser.CreationResult res = TreeStructureParser.createStructure(base, entries);
        assertEquals(4, res.createdDirs);
        assertEquals(4, res.createdFiles);
        assertTrue(new File(base, "my-app/src/utils/helper.py").isFile());
        assertTrue(new File(base, "my-app/tests/test_main.py").isFile());
        assertTrue(new File(base, "my-app/README.md").isFile());
    }

    @Test
    public void parsesAsciiTreeWithoutSingleRoot() {
        String ascii = "starter/\n" +
            "+-- app/\n" +
            "|   +-- index.js\n" +
            "|   \\-- styles.css\n" +
            "`-- package.json";

        List<TreeStructureParser.TreeEntry> entries = TreeStructureParser.parse(ascii, false);
        assertEquals(4, entries.size());
        assertEquals("app", entries.get(0).relativePath);
        assertTrue(entries.get(0).isDirectory);
        assertEquals("app/index.js", entries.get(1).relativePath);
        assertFalse(entries.get(1).isDirectory);
        assertEquals("app/styles.css", entries.get(2).relativePath);
        assertEquals("package.json", entries.get(3).relativePath);
    }

    @Test
    public void parsesMarkdownBulletsAndTabIndentation() {
        String md = "- backend\n" +
            "  - routes\n" +
            "    * auth.py\n" +
            "    + users.py\n" +
            "  - Dockerfile";

        List<TreeStructureParser.TreeEntry> entries = TreeStructureParser.parse(md, true);
        assertEquals(5, entries.size());
        assertTrue(entries.get(0).isDirectory); // backend has children
        assertTrue(entries.get(1).isDirectory); // backend/routes has children
        assertEquals("backend/routes/auth.py", entries.get(2).relativePath);
        assertEquals("backend/routes/users.py", entries.get(3).relativePath);
        assertEquals("backend/Dockerfile", entries.get(4).relativePath);
        assertFalse(entries.get(4).isDirectory);
    }

    @Test
    public void parsesFlatPathsAndDotRoot() {
        String flat = ".\n" +
            "src/core/engine.kt\n" +
            "src/ui/MainScreen.kt\n" +
            "README.md";

        List<TreeStructureParser.TreeEntry> entries = TreeStructureParser.parse(flat, true);
        assertTrue(entries.stream().anyMatch(e -> e.relativePath.equals("src") && e.isDirectory));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath.equals("src/core") && e.isDirectory));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath.equals("src/core/engine.kt") && !e.isDirectory));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath.equals("src/ui/MainScreen.kt") && !e.isDirectory));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath.equals("README.md") && !e.isDirectory));
    }
}

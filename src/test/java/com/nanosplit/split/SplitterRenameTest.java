package com.nanosplit.split;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.nanosplit.config.AppConfig;
import com.nanosplit.index.Index;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * split.renameFrom/renameTo: a script generated for one database must be
 * redirectable to a differently-named one without hand-editing the file,
 * since the name is baked into CREATE DATABASE/USE/ALTER DATABASE statements
 * (and their physical file paths) with no server-side setting that redirects
 * it.
 */
public class SplitterRenameTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void renamesDatabaseIdentifierEverywhereItAppears() throws Exception {
        String sql = "USE [master]\r\n"
                + "GO\r\n"
                + "CREATE DATABASE [OldDb]\r\n"
                + " ON PRIMARY (NAME = N'OldDb', FILENAME = N'C:\\data\\OldDb.mdf')\r\n"
                + "GO\r\n"
                + "USE [OldDb]\r\n"
                + "GO\r\n"
                + "CREATE TABLE [dbo].[Foo]([Id] [int] NOT NULL)\r\n"
                + "GO\r\n"
                + "INSERT [dbo].[Foo] ([Id]) VALUES (1)\r\n"
                + "GO\r\n";
        File input = tmp.newFile("script.sql");
        Files.write(input.toPath(), sql.getBytes(StandardCharsets.UTF_8));
        File outDir = tmp.newFolder("out");

        AppConfig cfg = AppConfig.load(null, Arrays.asList(
                "input.file=" + input.getPath(),
                "output.dir=" + outDir.getPath(),
                "split.maxStatementsPerPart=100",
                "split.renameFrom=OldDb",
                "split.renameTo=NewDb"
        ));
        Splitter splitter = new Splitter(cfg);
        Index index = splitter.run(null);

        StringBuilder all = new StringBuilder();
        for (com.nanosplit.index.PartEntry part : index.parts()) {
            all.append(new String(Files.readAllBytes(index.partPath(part).toPath()), StandardCharsets.UTF_8));
        }
        String combined = all.toString();

        assertFalse("old identifier must not survive the rename", combined.contains("OldDb"));
        assertTrue("new identifier must appear in the database statements",
                combined.contains("CREATE DATABASE [NewDb]"));
        assertTrue(combined.contains("USE [NewDb]"));
        assertTrue("physical file path must also be renamed (same literal substring)",
                combined.contains("NewDb.mdf"));
        assertTrue("plain INSERT content must be untouched", combined.contains("INSERT [dbo].[Foo] ([Id]) VALUES (1)"));
    }

    @Test
    public void skipPatternCommentsOutMatchingStatements() throws Exception {
        String sql = "USE [master]\r\n"
                + "GO\r\n"
                // SSMS glues an object comment onto the front of CREATE USER -
                // the pattern (anchored at the start) must see past it.
                + "/****** Objeto:  User [DOMAIN\\SomeGroup] ******/\r\n"
                + "CREATE USER [DOMAIN\\SomeGroup]\r\n"
                + "GO\r\n"
                + "ALTER ROLE [db_owner] ADD MEMBER [DOMAIN\\SomeGroup]\r\n"
                + "GO\r\n"
                + "INSERT [dbo].[Foo] ([Id]) VALUES (1)\r\n"
                + "GO\r\n";
        File input = tmp.newFile("script2.sql");
        Files.write(input.toPath(), sql.getBytes(StandardCharsets.UTF_8));
        File outDir = tmp.newFolder("out2");

        AppConfig cfg = AppConfig.load(null, Arrays.asList(
                "input.file=" + input.getPath(),
                "output.dir=" + outDir.getPath(),
                "split.skipPattern=^(CREATE USER|ALTER ROLE\\s.*ADD MEMBER)\\b.*\\\\"
        ));
        Splitter splitter = new Splitter(cfg);
        Index index = splitter.run(null);

        String content = new String(
                Files.readAllBytes(index.partPath(index.parts().get(0)).toPath()), StandardCharsets.UTF_8);
        assertFalse("the real CREATE USER must not run", content.contains("\nCREATE USER [DOMAIN\\SomeGroup]"));
        assertTrue("the skipped statement stays visible as a comment",
                content.contains("-- [NanoSplit] skipped") && content.contains("CREATE USER [DOMAIN\\SomeGroup]"));
        assertTrue(content.contains("ADD MEMBER [DOMAIN\\SomeGroup]"));
        assertTrue("unrelated statements are untouched", content.contains("INSERT [dbo].[Foo] ([Id]) VALUES (1)"));
    }

    @Test
    public void blankRenameFromLeavesTextUntouched() throws Exception {
        File input = tmp.newFile("script.sql");
        Files.write(input.toPath(), "USE [SomeDb]\r\nGO\r\n".getBytes(StandardCharsets.UTF_8));
        File outDir = tmp.newFolder("out");

        AppConfig cfg = AppConfig.load(null, Arrays.asList(
                "input.file=" + input.getPath(),
                "output.dir=" + outDir.getPath()
        ));
        Splitter splitter = new Splitter(cfg);
        Index index = splitter.run(null);
        String content = new String(Files.readAllBytes(index.partPath(index.parts().get(0)).toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("USE [SomeDb]"));
    }
}

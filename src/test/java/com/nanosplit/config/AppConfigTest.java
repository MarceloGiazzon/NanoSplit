package com.nanosplit.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File writeProps(String content) throws IOException {
        File f = tmp.newFile("nanosplit.properties");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void unsetKeysFallBackToDefaults() throws Exception {
        File f = writeProps("db.name=Foo\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals("Foo", cfg.get("db.name"));
        assertEquals("localhost\\SQLEXPRESS", cfg.get("db.server")); // default, untouched
    }

    @Test
    public void cliSetOverridesFileValue() throws Exception {
        File f = writeProps("db.name=Foo\n");
        AppConfig cfg = AppConfig.load(f.getPath(), Arrays.asList("db.name=Bar"));
        assertEquals("Bar", cfg.get("db.name"));
    }

    @Test
    public void singleBackslashWindowsPathSurvives() throws Exception {
        // A very common real-world mistake: a Windows path pasted with single
        // backslashes instead of doubled ones. The lenient reader must not
        // mangle it into garbage (java.util.Properties would turn \P into P).
        File f = writeProps("output.dir=C:\\Program Files\\NanoSplit\\out\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals("C:\\Program Files\\NanoSplit\\out", cfg.get("output.dir"));
    }

    @Test
    public void doubledBackslashAlsoWorks() throws Exception {
        File f = writeProps("db.server=MYPC\\\\SQLEXPRESS\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals("MYPC\\SQLEXPRESS", cfg.get("db.server"));
    }

    @Test
    public void booleanParsingAcceptsCommonSpellings() throws Exception {
        File f = writeProps("split.carryContext=no\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals(false, cfg.bool("split.carryContext"));
    }

    @Test
    public void sizeParsingDelegatesToSizes() throws Exception {
        File f = writeProps("split.maxBytesPerPart=128MB\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals(128L * 1024 * 1024, cfg.size("split.maxBytesPerPart"));
    }

    @Test
    public void relativePathResolvesAgainstConfigDirectory() throws Exception {
        File f = writeProps("output.dir=parts\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        File resolved = cfg.path("output.dir", false);
        assertEquals(new File(tmp.getRoot(), "parts").getCanonicalFile(), resolved);
    }

    @Test
    public void unknownKeysAreReportedNotSilentlyDropped() throws Exception {
        File f = writeProps("totally.unknown.key=x\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertTrue(cfg.unknownKeys().contains("totally.unknown.key"));
    }

    @Test
    public void interpolationSubstitutesEarlierValues() throws Exception {
        File f = writeProps("a=hello\nb=${a} world\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals("hello world", cfg.get("b"));
    }

    @Test
    public void redactedMasksPasswordButKeepsOtherValues() throws Exception {
        File f = writeProps("db.password=secret123\ndb.name=Foo\n");
        AppConfig cfg = AppConfig.load(f.getPath(), null);
        assertEquals("***", cfg.redacted().get("db.password"));
        assertEquals("Foo", cfg.redacted().get("db.name"));
    }
}

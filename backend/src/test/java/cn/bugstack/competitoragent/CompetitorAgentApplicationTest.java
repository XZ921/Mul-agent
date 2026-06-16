package cn.bugstack.competitoragent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompetitorAgentApplicationTest {

    @TempDir
    Path tempDir;

    private final String originalJavaTmpDir = System.getProperty("java.io.tmpdir");
    private final String originalUserHome = System.getProperty("user.home");
    private final String originalPlaywrightDriverTmpDir = System.getProperty("playwright.driver.tmpdir");

    @AfterEach
    void restoreSystemProperties() {
        System.setProperty("java.io.tmpdir", originalJavaTmpDir);
        System.setProperty("user.home", originalUserHome);
        restoreOptionalSystemProperty("playwright.driver.tmpdir", originalPlaywrightDriverTmpDir);
    }

    @Test
    void shouldFallbackToUserWritableTempDirectoryWhenJavaTmpDirIsInvalid() throws Exception {
        Path invalidTmpPath = Files.createFile(tempDir.resolve("not-a-directory"));
        Path fakeUserHome = tempDir.resolve("home");
        Path expectedFallback = fakeUserHome.resolve(".competitor-agent").resolve("tmp");
        System.setProperty("java.io.tmpdir", invalidTmpPath.toString());
        System.setProperty("user.home", fakeUserHome.toString());

        CompetitorAgentApplication.ensureWritableJavaTempDirectory();

        assertEquals(expectedFallback.toString(), System.getProperty("java.io.tmpdir"));
        assertTrue(Files.isDirectory(expectedFallback));
    }

    @Test
    void shouldKeepJavaTmpDirWhenCurrentDirectoryIsWritable() {
        System.setProperty("java.io.tmpdir", tempDir.toString());

        CompetitorAgentApplication.ensureWritableJavaTempDirectory();

        assertEquals(tempDir.toString(), System.getProperty("java.io.tmpdir"));
    }

    @Test
    void shouldFallbackToUserWritableDirectoryWhenPlaywrightDriverTmpDirIsInvalid() throws Exception {
        Path invalidTmpPath = Files.createFile(tempDir.resolve("not-a-playwright-directory"));
        Path fakeUserHome = tempDir.resolve("home");
        Path expectedFallback = fakeUserHome.resolve(".competitor-agent").resolve("playwright-driver");
        System.setProperty("playwright.driver.tmpdir", invalidTmpPath.toString());
        System.setProperty("user.home", fakeUserHome.toString());

        CompetitorAgentApplication.ensureWritablePlaywrightDriverTempDirectory();

        assertEquals(expectedFallback.toString(), System.getProperty("playwright.driver.tmpdir"));
        assertTrue(Files.isDirectory(expectedFallback));
    }

    @Test
    void shouldKeepPlaywrightDriverTmpDirWhenCurrentDirectoryIsWritable() {
        System.setProperty("playwright.driver.tmpdir", tempDir.toString());

        CompetitorAgentApplication.ensureWritablePlaywrightDriverTempDirectory();

        assertEquals(tempDir.toString(), System.getProperty("playwright.driver.tmpdir"));
    }

    private void restoreOptionalSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}

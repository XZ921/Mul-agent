package cn.bugstack.competitoragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * AI 竞品分析 Agent 协作系统 — 启动入口
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CompetitorAgentApplication {

    private static final String PLAYWRIGHT_DRIVER_TMPDIR_PROPERTY = "playwright.driver.tmpdir";

    public static void main(String[] args) {
        ensureWritableJavaTempDirectory();
        ensureWritablePlaywrightDriverTempDirectory();
        SpringApplication.run(CompetitorAgentApplication.class, args);
    }

    /**
     * 启动 Web 容器前先确认 JVM 临时目录可写，避免 Windows 启动环境缺少 TEMP/TMP 时回退到 C:\WINDOWS 导致 Tomcat 启动失败。
     */
    static void ensureWritableJavaTempDirectory() {
        String currentTmpDir = System.getProperty("java.io.tmpdir");
        if (isWritableDirectory(currentTmpDir)) {
            return;
        }

        Path fallbackTmpDir = Path.of(resolveUserHome(), ".competitor-agent", "tmp");
        try {
            Files.createDirectories(fallbackTmpDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建应用临时目录: " + fallbackTmpDir, e);
        }
        if (!isWritableDirectory(fallbackTmpDir.toString())) {
            throw new IllegalStateException("应用临时目录不可写: " + fallbackTmpDir);
        }
        System.setProperty("java.io.tmpdir", fallbackTmpDir.toString());
    }

    /**
     * Playwright Java driver 支持 playwright.driver.tmpdir；显式设置后可绕开 JDK 已缓存的错误默认临时目录。
     */
    static void ensureWritablePlaywrightDriverTempDirectory() {
        String configuredTmpDir = System.getProperty(PLAYWRIGHT_DRIVER_TMPDIR_PROPERTY);
        if (isWritableDirectory(configuredTmpDir)) {
            return;
        }
        Path fallbackDriverTmpDir = Path.of(resolveUserHome(), ".competitor-agent", "playwright-driver");
        try {
            Files.createDirectories(fallbackDriverTmpDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 Playwright driver 临时目录: " + fallbackDriverTmpDir, e);
        }
        if (!isWritableDirectory(fallbackDriverTmpDir.toString())) {
            throw new IllegalStateException("Playwright driver 临时目录不可写: " + fallbackDriverTmpDir);
        }
        System.setProperty(PLAYWRIGHT_DRIVER_TMPDIR_PROPERTY, fallbackDriverTmpDir.toString());
    }

    private static String resolveUserHome() {
        String userHome = System.getProperty("user.home");
        return userHome == null || userHome.isBlank() ? "." : userHome;
    }

    private static boolean isWritableDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return false;
        }
        try {
            Path tempDirectory = Path.of(directory);
            if (!Files.isDirectory(tempDirectory)) {
                return false;
            }
            // 真实创建探针文件，但避免使用 Files.createTempFile，防止 JDK 提前缓存错误的 java.io.tmpdir。
            Path probeFile = tempDirectory.resolve(".competitor-agent-write-test-" + UUID.randomUUID() + ".tmp");
            Files.createFile(probeFile);
            Files.deleteIfExists(probeFile);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}

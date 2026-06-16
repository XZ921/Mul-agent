package cn.bugstack.competitoragent.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 嵌入式 Tomcat 工作目录配置。
 * <p>
 * Windows 启动环境缺少 TEMP/TMP 时，JDK 可能把临时目录回退到 C:\WINDOWS，导致 Tomcat 无权限创建 base/docbase。
 * 这里显式指定 baseDirectory 与 documentRoot，避免 Web 容器再依赖 JVM 默认临时目录。
 */
@Configuration
public class TomcatWorkDirectoryConfig {

    private static final String BASE_DIR_PROPERTY = "server.tomcat.basedir";
    private static final String DOCUMENT_ROOT_PROPERTY = "server.tomcat.document-root";

    private final Environment environment;

    public TomcatWorkDirectoryConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatWorkDirectoryCustomizer() {
        return factory -> {
            Path baseDirectory = ensureWritableDirectory(resolveDirectory(BASE_DIR_PROPERTY, "tomcat"));
            Path documentRoot = ensureWritableDirectory(resolveDirectory(DOCUMENT_ROOT_PROPERTY, "tomcat-docbase"));
            factory.setBaseDirectory(baseDirectory.toFile());
            factory.setDocumentRoot(documentRoot.toFile());
        };
    }

    private Path resolveDirectory(String propertyName, String defaultDirectoryName) {
        String configuredDirectory = environment.getProperty(propertyName);
        if (StringUtils.hasText(configuredDirectory)) {
            return Path.of(configuredDirectory);
        }
        return Path.of(resolveUserHome(), ".competitor-agent", defaultDirectoryName);
    }

    private String resolveUserHome() {
        String userHome = System.getProperty("user.home");
        return StringUtils.hasText(userHome) ? userHome : ".";
    }

    /**
     * 创建并验证目录可写；使用普通探针文件，避免触发 JDK 对 java.io.tmpdir 的静态缓存。
     */
    static Path ensureWritableDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            Path probeFile = directory.resolve(".competitor-agent-write-test-" + UUID.randomUUID() + ".tmp");
            Files.createFile(probeFile);
            Files.deleteIfExists(probeFile);
            return directory;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Tomcat 工作目录不可写: " + directory, e);
        }
    }
}

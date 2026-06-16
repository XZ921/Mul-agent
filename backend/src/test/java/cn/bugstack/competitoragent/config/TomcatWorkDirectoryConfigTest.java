package cn.bugstack.competitoragent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomcatWorkDirectoryConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateConfiguredTomcatWorkDirectories() {
        Path baseDirectory = tempDir.resolve("tomcat-base");
        Path documentRoot = tempDir.resolve("tomcat-docbase");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.tomcat.basedir", baseDirectory.toString())
                .withProperty("server.tomcat.document-root", documentRoot.toString());
        TomcatWorkDirectoryConfig config = new TomcatWorkDirectoryConfig(environment);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        config.tomcatWorkDirectoryCustomizer().customize(factory);

        assertTrue(Files.isDirectory(baseDirectory));
        assertTrue(Files.isDirectory(documentRoot));
        assertEquals(documentRoot.toFile(), factory.getDocumentRoot());
    }
}

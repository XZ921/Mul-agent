package cn.bugstack.competitoragent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSecurityUtilsTest {

    @Test
    void shouldAllowOnlyHttpAndHttps() {
        assertTrue(UrlSecurityUtils.isHttpUrl("https://www.notion.so"));
        assertTrue(UrlSecurityUtils.isHttpUrl("http://example.com/docs"));
        assertFalse(UrlSecurityUtils.isHttpUrl("file:///C:/Windows/System32/drivers/etc/hosts"));
        assertFalse(UrlSecurityUtils.isHttpUrl("javascript:alert(1)"));
        assertFalse(UrlSecurityUtils.isHttpUrl("ftp://example.com/file.txt"));
    }

    @Test
    void shouldRequireHttpsForSensitiveEndpoints() {
        assertTrue(UrlSecurityUtils.isHttpsUrl("https://serpapi.com/search"));
        assertFalse(UrlSecurityUtils.isHttpsUrl("http://serpapi.com/search"));
    }
}

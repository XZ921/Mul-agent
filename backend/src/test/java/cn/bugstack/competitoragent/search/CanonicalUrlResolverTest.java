package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalUrlResolverTest {

    @Test
    void shouldPreserveSchemeAndPortForLocalRssFeedUrl() {
        CanonicalUrlResolver resolver = new CanonicalUrlResolver();

        String canonicalUrl = resolver.canonicalize("http://127.0.0.1:18080/feed.xml");

        assertEquals("http://127.0.0.1:18080/feed.xml", canonicalUrl);
    }
}

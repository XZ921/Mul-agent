package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchEnginePropertiesTest {

    @Test
    void shouldResolveAliasOnlyToEnabledEngine() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.get("bing").setEnabled(false);
        properties.get("duckduckgo").setEnabled(true);

        assertEquals("duckduckgo", properties.normalizeEngineKey("ddg"));
        assertEquals("duckduckgo", properties.resolveAvailableEngineKey("ddg"));
        assertEquals(List.of("duckduckgo"), properties.resolveEnabledEngineKeys("ddg", List.of("bing")));
    }
}

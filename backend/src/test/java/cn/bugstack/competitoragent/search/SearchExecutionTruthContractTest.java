package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchExecutionTruthContractTest {

    private final SearchPolicyResolver resolver = new SearchPolicyResolver();

    @Test
    void shouldRemoveHeuristicFromFormalFallbackOrder() {
        assertEquals(List.of("PLANNED", "BROWSER", "HTTP"), resolver.resolveFallbackOrder("HYBRID", true));
        assertEquals(List.of("PLANNED", "HTTP"), resolver.resolveFallbackOrder("HYBRID", false));
        assertEquals(List.of("PLANNED", "HTTP"), resolver.resolveFallbackOrder("HEURISTIC_ONLY", false));
    }

    @Test
    void shouldResolveSearchEngineByAliasAndEnabledFlag() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.get("duckduckgo").setEnabled(true);

        assertEquals("duckduckgo", resolver.resolveSearchEngineKey("ddg", properties));
        assertEquals("duckduckgo", properties.resolveAvailableEngineKey("ddg"));
        assertFalse(properties.resolveEnabledEngineKeys("ddg", List.of()).isEmpty());
    }
}

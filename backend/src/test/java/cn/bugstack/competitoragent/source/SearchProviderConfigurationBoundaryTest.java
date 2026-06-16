package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderConfigurationBoundaryTest {

    @Test
    void shouldKeepProviderPropertiesFocusedOnRoutingAndRuntimeOnly() {
        Set<String> forbiddenBusinessFields = Set.of(
                "sourceTypes",
                "contentScopes",
                "primaryTools",
                "auxiliaryTools",
                "queryTemplates",
                "updatePolicy"
        );

        assertThat(Arrays.stream(SearchProviderProperties.class.getDeclaredFields())
                .map(Field::getName)
                .filter(forbiddenBusinessFields::contains)
                .toList()).isEmpty();
    }

    @Test
    void shouldKeepRoutePropertiesFreeFromBusinessSourceFamilyFields() {
        Set<String> forbiddenBusinessFields = Set.of(
                "sourceFamilyKey",
                "sourceTypes",
                "contentScopes",
                "primaryTools",
                "queryTemplates"
        );

        assertThat(Arrays.stream(SearchProviderProperties.ProviderRouteProperties.class.getDeclaredFields())
                .map(Field::getName)
                .filter(forbiddenBusinessFields::contains)
                .toList()).isEmpty();
    }
}

package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceTrustTierTest {

    @Test
    void shouldExposeStableWeightAndDisplayName() {
        assertEquals(1.0D, SourceTrustTier.HIGH.getWeight());
        assertEquals("高可信", SourceTrustTier.HIGH.getDisplayName());
        assertEquals(0.75D, SourceTrustTier.MEDIUM.getWeight());
        assertEquals("中可信", SourceTrustTier.MEDIUM.getDisplayName());
        assertEquals(0.5D, SourceTrustTier.LOW.getWeight());
        assertEquals("低可信", SourceTrustTier.LOW.getDisplayName());
    }
}

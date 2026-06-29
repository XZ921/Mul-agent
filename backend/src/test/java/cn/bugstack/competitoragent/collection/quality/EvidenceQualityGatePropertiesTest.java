package cn.bugstack.competitoragent.collection.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceQualityGatePropertiesTest {

    @Test
    void defaultAuthSignalsShouldCoverTask66ChineseGate() {
        EvidenceQualityGateProperties properties = new EvidenceQualityGateProperties();

        assertThat(properties.getAuthSignals())
                .contains("验证码", "智能验证", "由极验提供技术支持");
        assertThat(properties.getNavigationShellLinkRatioThreshold()).isEqualTo(0.55D);
    }
}

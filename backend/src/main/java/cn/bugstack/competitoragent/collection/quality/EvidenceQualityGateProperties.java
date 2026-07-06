package cn.bugstack.competitoragent.collection.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证据质量门禁配置。
 * 鉴权/验证码信号必须配置化，避免把 task66 命中的中文站点特征硬编码死在单个类中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.evidence-quality")
public class EvidenceQualityGateProperties {

    private boolean enabled = true;
    private int minUsefulParagraphLength = 80;
    private int longOfficialContentChars = 2000;
    private double navigationShellLinkRatioThreshold = 0.55D;
    private double authGateScoreCap = 0.20D;
    private double navigationShellScoreCap = 0.30D;
    private double rootEntryScoreCap = 0.45D;
    /**
     * 放宽官方长正文质量门后，需要一个反向闸门来兜住已知聚合/导航站。
     * 这里保留成配置项，便于后续继续扩容，而不是把 explinks 这类站点硬编码散落到多个判断里。
     */
    private List<String> aggregatorDomains = List.of(
            "explinks.com"
    );
    private List<String> authSignals = List.of(
            "验证码",
            "智能验证",
            "检测中",
            "登录",
            "注册",
            "请点击此处重试",
            "网络超时",
            "由极验提供技术支持",
            "完成身份信息填写",
            "去填写",
            "去接受邀请"
    );
}

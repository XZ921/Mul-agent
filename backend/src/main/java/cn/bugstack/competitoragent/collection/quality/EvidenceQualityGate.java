package cn.bugstack.competitoragent.collection.quality;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 证据质量门禁。
 * 它不决定“来源是否官方”，而是判断“这份正文能不能作为当前字段的正式证据”。
 */
@Component
public class EvidenceQualityGate {

    private static final List<String> NAVIGATION_MARKERS = List.of(
            "首页", "文档中心", "管理中心", "帮助中心", "联系我们", "友情链接", "登录", "注册"
    );

    private final EvidenceQualityGateProperties properties;

    public EvidenceQualityGate(EvidenceQualityGateProperties properties) {
        this.properties = properties == null ? new EvidenceQualityGateProperties() : properties;
    }

    /**
     * 统一评估正文质量。
     * 这里必须把“候选排名高分”和“正文真正可用”拆开，否则 task66 里官方首页导航壳仍会被误当成高质量证据。
     */
    public EvidenceQualityVerdict evaluate(EvidenceQualityContext context,
                                           String content,
                                           List<String> existingSignals,
                                           Double candidateQualityScore) {
        List<EvidenceQualityIssue> issues = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        if (existingSignals != null) {
            signals.addAll(existingSignals);
        }

        String safeContent = content == null ? "" : content;
        double sourceScore = isOfficial(context == null ? null : context.getSourceType(),
                context == null ? null : context.getUrl()) ? 0.90D : 0.60D;
        double contentScore = calculateContentUsabilityScore(safeContent, issues, signals);
        double taskScore = taskRelevanceScore(context, safeContent, issues, signals);

        if (isAuthGateContent(safeContent)) {
            issues.add(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
            signals.add("AUTH_GATE_DETECTED");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            contentScore = Math.min(contentScore, properties.getAuthGateScoreCap());
        }

        if (isRootEntry(context == null ? null : context.getUrl())) {
            issues.add(EvidenceQualityIssue.ROOT_ENTRY_PAGE);
            signals.add("ROOT_ENTRY_WEAK_CONTENT");
            if (contentScore <= properties.getRootEntryScoreCap() || taskScore <= properties.getRootEntryScoreCap()) {
                signals.add("EVIDENCE_REPAIR_REQUIRED");
            }
            taskScore = Math.min(taskScore, properties.getRootEntryScoreCap());
        }

        double usabilityScore = Math.min(sourceScore, Math.min(contentScore, taskScore));
        if (candidateQualityScore != null
                && candidateQualityScore >= 0.85D
                && usabilityScore <= 0.45D) {
            issues.add(EvidenceQualityIssue.HIGH_TRUST_LOW_USABILITY);
            issues.add(EvidenceQualityIssue.SCORE_CONTRADICTION_DETECTED);
            signals.add("HIGH_TRUST_LOW_USABILITY");
            signals.add("SCORE_CONTRADICTION_DETECTED");
        }

        return EvidenceQualityVerdict.builder()
                .sourceAuthenticityScore(sourceScore)
                .contentUsabilityScore(contentScore)
                .taskRelevanceScore(taskScore)
                .evidenceUsabilityScore(usabilityScore)
                .issues(issues.stream().distinct().toList())
                .qualitySignals(signals.stream().distinct().toList())
                .repairRequired(signals.contains("EVIDENCE_REPAIR_REQUIRED"))
                .build();
    }

    /**
     * 迁移期兼容旧调用点。
     * 新链路必须优先传入 EvidenceQualityContext，避免质量门禁退回“只看 URL/sourceType”的老行为。
     */
    @Deprecated
    public EvidenceQualityVerdict evaluate(String url,
                                           String sourceType,
                                           String content,
                                           List<String> existingSignals,
                                           Double candidateQualityScore) {
        return evaluate(EvidenceQualityContext.builder()
                        .url(url)
                        .sourceType(sourceType)
                        .build(),
                content,
                existingSignals,
                candidateQualityScore);
    }

    /**
     * 正文可用性判断优先防误判。
     * 这里不追求一步到位理解整页，只先把导航壳、链接农场和薄正文压低，防止它们越过报告级门槛。
     */
    private double calculateContentUsabilityScore(String content,
                                                  List<EvidenceQualityIssue> issues,
                                                  List<String> signals) {
        if (!StringUtils.hasText(content)) {
            issues.add(EvidenceQualityIssue.WEAK_MAIN_CONTENT);
            signals.add("WEAK_MAIN_CONTENT");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            return 0.0D;
        }

        String normalizedContent = normalizeContent(content);
        int usefulLength = normalizedContent.length();
        int navigationHits = countContains(content, NAVIGATION_MARKERS);
        double navigationLinkRatio = estimateNavigationLinkRatio(content);
        boolean navigationShell = navigationHits >= 4
                && navigationLinkRatio >= properties.getNavigationShellLinkRatioThreshold();
        boolean thinContent = usefulLength < properties.getMinUsefulParagraphLength();

        double contentScore = thinContent ? 0.35D : 0.70D;
        if (thinContent) {
            issues.add(EvidenceQualityIssue.WEAK_MAIN_CONTENT);
            signals.add("WEAK_MAIN_CONTENT");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
        }
        if (navigationShell) {
            issues.add(EvidenceQualityIssue.NAVIGATION_SHELL);
            signals.add("NAVIGATION_SHELL_DETECTED");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            contentScore = Math.min(contentScore, properties.getNavigationShellScoreCap());
        }
        if (looksLikeLinkFarm(content, usefulLength)) {
            issues.add(EvidenceQualityIssue.LINK_FARM_WITHOUT_BODY);
            signals.add("LINK_FARM_WITHOUT_BODY");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            contentScore = Math.min(contentScore, properties.getNavigationShellScoreCap());
        }
        return contentScore;
    }

    /**
     * 字段相关性门禁。
     * 正文质量不能只看长度和官方域名，还必须覆盖当前字段证据路径期待的业务信号。
     */
    private double taskRelevanceScore(EvidenceQualityContext context,
                                      String content,
                                      List<EvidenceQualityIssue> issues,
                                      List<String> signals) {
        List<String> expectedSignals = resolveExpectedSignals(context, signals);
        if (expectedSignals.isEmpty()) {
            signals.add("FIELD_CONTEXT_MISSING_QUALITY_GATE");
            return 0.50D;
        }

        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String expectedSignal : expectedSignals) {
            if (StringUtils.hasText(expectedSignal)
                    && normalized.contains(expectedSignal.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        if (hits == 0) {
            issues.add(EvidenceQualityIssue.LOW_TASK_KEYWORD_DENSITY);
            signals.add("FIELD_RELEVANCE_WEAK");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            return 0.35D;
        }
        if (hits == 1) {
            signals.add("FIELD_RELEVANCE_PARTIAL");
            return 0.55D;
        }
        return 0.75D;
    }

    /**
     * 01 阶段只把 requiredCoverageFields / blockingCoverageFields / coverageQueryIntents 下发给 Collector。
     * 因此 02 先允许从节点级轻量视图反推一组“够用的业务信号”，避免质量门禁必须等待 field-path planner 完整落地。
     */
    private List<String> resolveExpectedSignals(EvidenceQualityContext context, List<String> signals) {
        if (context == null) {
            return List.of();
        }
        if (context.getExpectedSignals() != null && !context.getExpectedSignals().isEmpty()) {
            return context.getExpectedSignals();
        }

        LinkedHashSet<String> derivedSignals = new LinkedHashSet<>();
        String singleCoverageField = resolveSingleCoverageField(context);
        if ("pricing".equalsIgnoreCase(singleCoverageField)) {
            derivedSignals.addAll(List.of("计费", "定价", "免费配额", "billing", "pricing"));
        } else if ("coreFeatures".equalsIgnoreCase(singleCoverageField)) {
            derivedSignals.addAll(List.of("API", "SDK", "功能", "能力", "developer"));
        }
        if (context.getCoverageQueryIntents() != null) {
            for (String intent : context.getCoverageQueryIntents()) {
                if (!StringUtils.hasText(intent)) {
                    continue;
                }
                String normalizedIntent = intent.trim().toUpperCase(Locale.ROOT);
                if (derivedSignals.isEmpty() && (normalizedIntent.contains("PRICING") || normalizedIntent.contains("BILLING"))) {
                    derivedSignals.addAll(List.of("计费", "定价", "免费配额", "billing", "pricing"));
                }
                if (derivedSignals.isEmpty()
                        && (normalizedIntent.contains("DOCS") || normalizedIntent.contains("API") || normalizedIntent.contains("SDK"))) {
                    derivedSignals.addAll(List.of("API", "SDK", "文档", "开发者", "developer"));
                }
                if (derivedSignals.isEmpty()
                        && (normalizedIntent.contains("POLICY") || normalizedIntent.contains("TERMS"))) {
                    derivedSignals.addAll(List.of("协议", "规则", "限制", "terms", "policy"));
                }
            }
        }
        if (!derivedSignals.isEmpty()) {
            signals.add("FIELD_CONTEXT_FALLBACK_FROM_NODE_CONFIG");
        }
        return new ArrayList<>(derivedSignals);
    }

    /**
     * 鉴权墙识别不能把“授权登录能力”误判成“登录注册壳页”。
     * 因此这里优先识别验证码/极验/重试/检测中等强信号，弱信号则要求组合出现。
     */
    private boolean isAuthGateContent(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        if (containsAny(content, List.of(
                "验证码",
                "智能验证",
                "检测中",
                "请点击此处重试",
                "网络超时",
                "由极验提供技术支持",
                "完成身份信息填写",
                "去接受邀请"
        ))) {
            return true;
        }
        return content.contains("登录") && content.contains("注册");
    }

    private String resolveSingleCoverageField(EvidenceQualityContext context) {
        if (StringUtils.hasText(context.getFieldName())) {
            return context.getFieldName();
        }
        if (context.getRequiredCoverageFields() != null && context.getRequiredCoverageFields().size() == 1) {
            return context.getRequiredCoverageFields().get(0);
        }
        return null;
    }

    private boolean containsAny(String content, List<String> markers) {
        if (!StringUtils.hasText(content) || markers == null || markers.isEmpty()) {
            return false;
        }
        return markers.stream()
                .filter(StringUtils::hasText)
                .anyMatch(content::contains);
    }

    private int countContains(String content, List<String> markers) {
        int count = 0;
        for (String marker : markers) {
            if (StringUtils.hasText(marker) && content.contains(marker)) {
                count++;
            }
        }
        return count;
    }

    private double estimateNavigationLinkRatio(String content) {
        if (!StringUtils.hasText(content)) {
            return 1.0D;
        }
        int navigationHits = countContains(content, NAVIGATION_MARKERS);
        int linkHints = countContains(content, List.of("http://", "https://", "[", "]", "(", ")", "|", ">", "»"));
        int denominator = Math.max(1, navigationHits + estimateParagraphCount(content));
        return Math.min(1.0D, (navigationHits + linkHints) / (double) denominator);
    }

    private int estimateParagraphCount(String content) {
        String[] parts = content.split("[\\r\\n。！？!?]");
        int count = 0;
        for (String part : parts) {
            if (normalizeContent(part).length() >= properties.getMinUsefulParagraphLength()) {
                count++;
            }
        }
        return count;
    }

    private boolean looksLikeLinkFarm(String content, int usefulLength) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        int linkHints = countContains(content, List.of("http://", "https://", "[", "]", "(", ")", "|"));
        return linkHints >= 6 && usefulLength < properties.getMinUsefulParagraphLength() * 2;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("\\s+", "")
                .replace("|", "")
                .replace("[", "")
                .replace("]", "");
    }

    private boolean isRootEntry(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            return path == null || path.isBlank() || "/".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isOfficial(String sourceType, String url) {
        String normalizedSourceType = sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT);
        if (normalizedSourceType.contains("OFFICIAL") || normalizedSourceType.contains("DOCS")) {
            return true;
        }
        return StringUtils.hasText(url)
                && (url.startsWith("https://") || url.startsWith("http://"));
    }
}

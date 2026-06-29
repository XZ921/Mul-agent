package cn.bugstack.competitoragent.collection;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tavily 预抓正文轻量结构化块分类器。
 * 这里刻意只做“低风险可解释命中”，优先防误判，不承担复杂页面理解职责。
 */
@Component
public class TavilyPrefetchedContentBlockClassifier {

    private static final int MIN_PARAGRAPH_LENGTH = 70;
    private static final List<String> NAVIGATION_MARKERS = List.of(
            "主站", "首页", "帮助中心", "友情链接", "登录", "注册", "联系我们", "立即加入", "管理中心", "文档中心"
    );

    public List<StructuredContentBlock> classify(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<String> paragraphs = splitParagraphs(content);
        List<StructuredContentBlock> blocks = new ArrayList<>();
        addBlockIfMatched(blocks, paragraphs, "PRICING_BLOCK",
                List.of("价格", "定价", "套餐", "计费", "pricing", "plan", "billing"));
        addBlockIfMatched(blocks, paragraphs, "LIMITATION_OR_POLICY_BLOCK",
                List.of("限制", "风险", "审核", "规则", "协议", "条款", "limitation", "risk", "policy", "agreement"));
        addBlockIfMatched(blocks, paragraphs, "DEVELOPER_DOCS_BLOCK",
                List.of("api", "sdk", "接口", "开发文档", "guide", "reference", "授权", "用户管理", "开发者"));
        addBlockIfMatched(blocks, paragraphs, "FEATURE_BLOCK",
                List.of("功能", "能力", "场景", "产品介绍", "服务支持"));
        return blocks;
    }

    /**
     * 结构化块提取坚持“命中充分再产出”的原则。
     * 只有段落先通过防噪门槛，再命中至少两个同类关键词，才允许生成结构化块，
     * 避免把导航壳、标题碎片或模糊配额描述误标成报告级证据块。
     */
    private void addBlockIfMatched(List<StructuredContentBlock> blocks,
                                   List<String> paragraphs,
                                   String blockType,
                                   List<String> keywords) {
        for (String paragraph : paragraphs) {
            if (!isUsefulParagraph(paragraph)) {
                continue;
            }
            int hits = countHits(paragraph, keywords);
            if (hits < 2) {
                continue;
            }
            blocks.add(StructuredContentBlock.builder()
                    .blockType(blockType)
                    .title(resolveBlockTitle(blockType))
                    .content(paragraph.trim())
                    .qualitySignal("TAVILY_BLOCK_CLASSIFIED:blockConfidence=0.72;blockEvidenceReason=paragraph-body keywordHits=" + hits)
                    .build());
            return;
        }
    }

    /**
     * Tavily raw_content 常含多行文本，这里按行切段并去重空行。
     * 但单个业务段落往往会因为换行被拆成“标题 + 正文”多行，所以这里按“空行断段、连续正文合并”处理，
     * 既保留轻量规则分类的可审计性，也避免把真实正文切碎后误判成短段落。
     */
    private List<String> splitParagraphs(String content) {
        String[] lines = content.split("\\R", -1);
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (StringUtils.hasText(line)) {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line.trim());
                continue;
            }
            if (current.length() > 0) {
                paragraphs.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            paragraphs.add(current.toString().trim());
        }
        return paragraphs;
    }

    /**
     * 防噪门槛优先压住导航壳和入口页菜单。
     * 如果段落过短，或者导航关键词密度过高，就宁可不产出结构化块，
     * 后续交给 repair/field-first 证据路径继续补强，而不是在这里制造误判。
     */
    private boolean isUsefulParagraph(String paragraph) {
        if (!StringUtils.hasText(paragraph) || paragraph.length() < MIN_PARAGRAPH_LENGTH) {
            return false;
        }
        int navHits = countHits(paragraph, NAVIGATION_MARKERS);
        return navHits < 3;
    }

    private int countHits(String paragraph, List<String> keywords) {
        String normalized = paragraph == null ? "" : paragraph.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    private String resolveBlockTitle(String blockType) {
        return switch (blockType) {
            case "PRICING_BLOCK" -> "Pricing";
            case "LIMITATION_OR_POLICY_BLOCK" -> "Limitation or Policy";
            case "DEVELOPER_DOCS_BLOCK" -> "Developer Docs";
            case "FEATURE_BLOCK" -> "Feature";
            default -> blockType;
        };
    }
}

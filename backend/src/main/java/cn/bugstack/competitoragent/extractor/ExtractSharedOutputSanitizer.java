package cn.bugstack.competitoragent.extractor;

import cn.bugstack.competitoragent.extractor.input.ExtractorCompetitorInput;
import cn.bugstack.competitoragent.extractor.input.ExtractorInputPackage;
import cn.bugstack.competitoragent.workflow.contract.CompetitorKnowledgeDraft;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * extract_schema 运行态输出瘦身工具。
 * <p>
 * 这里统一负责把跨节点共享所需的证据视图裁剪为轻量版本，避免 extractor 自己输出一套、
 * shared projection 再实现另一套，导致字段边界逐渐漂移。
 */
public final class ExtractSharedOutputSanitizer {

    private ExtractSharedOutputSanitizer() {
    }

    /**
     * 节点级 results 里允许保留 coverage、issueFlags 等诊断字段，
     * 但凡嵌套的 downstreamEvidenceViews 都要统一裁剪掉正文与 structuredPayload。
     */
    public static List<Map<String, Object>> slimResultSummaries(List<Map<String, Object>> results,
                                                                ObjectMapper objectMapper) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> slimResults = new ArrayList<>();
        for (Map<String, Object> result : results) {
            slimResults.add(slimResultSummary(result, objectMapper));
        }
        return slimResults;
    }

    public static List<CompetitorKnowledgeDraft> slimDrafts(List<CompetitorKnowledgeDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<CompetitorKnowledgeDraft> slimDrafts = new ArrayList<>();
        for (CompetitorKnowledgeDraft draft : drafts) {
            if (draft != null) {
                slimDrafts.add(slimDraft(draft));
            }
        }
        return slimDrafts;
    }

    public static CompetitorKnowledgeDraft slimDraft(CompetitorKnowledgeDraft draft) {
        if (draft == null) {
            return null;
        }
        return CompetitorKnowledgeDraft.builder()
                .competitorName(draft.getCompetitorName())
                .officialUrl(draft.getOfficialUrl())
                .summary(draft.getSummary())
                .positioning(draft.getPositioning())
                .targetUsers(copyList(draft.getTargetUsers()))
                .coreFeatures(copyList(draft.getCoreFeatures()))
                .pricing(draft.getPricing())
                .strengths(copyList(draft.getStrengths()))
                .weaknesses(copyList(draft.getWeaknesses()))
                .sourceUrls(copyList(draft.getSourceUrls()))
                .evidenceFragments(slimEvidenceFragments(draft.getEvidenceFragments()))
                .sectionEvidenceBundles(slimSectionEvidenceBundles(draft.getSectionEvidenceBundles()))
                .downstreamEvidenceViews(slimEvidenceViews(draft.getDownstreamEvidenceViews()))
                .issueFlags(copyList(draft.getIssueFlags()))
                .evidenceCoverage(copyMap(draft.getEvidenceCoverage()))
                .fieldsExtracted(draft.getFieldsExtracted())
                .status(draft.getStatus())
                .build();
    }

    public static ExtractorInputPackage slimExtractorInputPackage(ExtractorInputPackage inputPackage) {
        if (inputPackage == null) {
            return null;
        }
        return ExtractorInputPackage.builder()
                .taskId(inputPackage.getTaskId())
                .nodeName(inputPackage.getNodeName())
                .planVersionId(inputPackage.getPlanVersionId())
                .branchKey(inputPackage.getBranchKey())
                .schemaId(inputPackage.getSchemaId())
                .dimensions(copyList(inputPackage.getDimensions()))
                .competitors(slimCompetitorInputs(inputPackage.getCompetitors()))
                .build();
    }

    public static List<DownstreamEvidenceView> slimEvidenceViews(List<DownstreamEvidenceView> views) {
        if (views == null || views.isEmpty()) {
            return List.of();
        }
        List<DownstreamEvidenceView> slimViews = new ArrayList<>();
        for (DownstreamEvidenceView view : views) {
            if (view != null) {
                slimViews.add(slimEvidenceView(view));
            }
        }
        return slimViews;
    }

    public static DownstreamEvidenceView slimEvidenceView(DownstreamEvidenceView view) {
        if (view == null) {
            return null;
        }
        return DownstreamEvidenceView.builder()
                .evidenceId(view.getEvidenceId())
                .competitorName(view.getCompetitorName())
                .sourceType(view.getSourceType())
                .title(view.getTitle())
                // 下游只允许拿 evidenceId / structured summary 做回查，不再跨节点透传正文。
                .content("")
                .sourceUrls(copyList(view.getSourceUrls()))
                .issueFlags(copyList(view.getIssueFlags()))
                .qualitySignals(copyList(view.getQualitySignals()))
                .structuredBlocks(slimEvidenceBlocks(view.getStructuredBlocks()))
                .structuredPayload(Map.of())
                .quality(view.getQuality())
                .build()
                .normalized();
    }

    public static List<DownstreamEvidenceBlock> slimEvidenceBlocks(List<DownstreamEvidenceBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DownstreamEvidenceBlock> slimBlocks = new ArrayList<>();
        for (DownstreamEvidenceBlock block : blocks) {
            if (block == null) {
                continue;
            }
            slimBlocks.add(DownstreamEvidenceBlock.builder()
                    .blockType(block.getBlockType())
                    .title(block.getTitle())
                    // structured block 允许保留摘要，但不再继续透传原始正文。
                    .summary(firstNonBlank(block.getSummary(), block.getContent()))
                    .content(null)
                    .qualitySignal(block.getQualitySignal())
                    .sourceUrls(copyList(block.getSourceUrls()))
                    .build()
                    .normalized());
        }
        return slimBlocks;
    }

    public static List<EvidenceFragment> slimEvidenceFragments(List<EvidenceFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<EvidenceFragment> slimFragments = new ArrayList<>();
        for (EvidenceFragment fragment : fragments) {
            if (fragment == null) {
                continue;
            }
            slimFragments.add(EvidenceFragment.builder()
                    .stage(fragment.getStage())
                    .competitorName(fragment.getCompetitorName())
                    .fieldName(fragment.getFieldName())
                    .fieldLabel(fragment.getFieldLabel())
                    .sectionKey(fragment.getSectionKey())
                    .sectionTitle(fragment.getSectionTitle())
                    .evidenceId(fragment.getEvidenceId())
                    .sourceUrl(fragment.getSourceUrl())
                    .title(fragment.getTitle())
                    // snippet 在跨节点 shared output 中容易重新带入长正文，这里只保留 formal evidence 引用。
                    .snippet(null)
                    .coverageStatus(fragment.getCoverageStatus())
                    .gapComment(fragment.getGapComment())
                    .issueFlags(copyList(fragment.getIssueFlags()))
                    .build()
                    .normalized());
        }
        return slimFragments;
    }

    public static List<SectionEvidenceBundle> slimSectionEvidenceBundles(List<SectionEvidenceBundle> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return List.of();
        }
        List<SectionEvidenceBundle> slimBundles = new ArrayList<>();
        for (SectionEvidenceBundle bundle : bundles) {
            if (bundle == null) {
                continue;
            }
            slimBundles.add(SectionEvidenceBundle.builder()
                    .stage(bundle.getStage())
                    .sectionType(bundle.getSectionType())
                    .sectionKey(bundle.getSectionKey())
                    .sectionTitle(bundle.getSectionTitle())
                    .summary(bundle.getSummary())
                    .gapSummary(bundle.getGapSummary())
                    .fieldNames(copyList(bundle.getFieldNames()))
                    .missingFields(copyList(bundle.getMissingFields()))
                    .sourceUrls(copyList(bundle.getSourceUrls()))
                    .issueFlags(copyList(bundle.getIssueFlags()))
                    .evidenceFragments(slimEvidenceFragments(bundle.getEvidenceFragments()))
                    .build()
                    .normalized());
        }
        return slimBundles;
    }

    private static Map<String, Object> slimResultSummary(Map<String, Object> result, ObjectMapper objectMapper) {
        LinkedHashMap<String, Object> slimResult = new LinkedHashMap<>();
        if (result == null || result.isEmpty()) {
            return slimResult;
        }
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if ("downstreamEvidenceViews".equals(entry.getKey())) {
                slimResult.put(entry.getKey(), convertAndSlimEvidenceViews(entry.getValue(), objectMapper));
                continue;
            }
            slimResult.put(entry.getKey(), entry.getValue());
        }
        return slimResult;
    }

    private static List<ExtractorCompetitorInput> slimCompetitorInputs(List<ExtractorCompetitorInput> competitors) {
        if (competitors == null || competitors.isEmpty()) {
            return List.of();
        }
        List<ExtractorCompetitorInput> slimCompetitors = new ArrayList<>();
        for (ExtractorCompetitorInput competitor : competitors) {
            if (competitor == null) {
                continue;
            }
            slimCompetitors.add(ExtractorCompetitorInput.builder()
                    .competitorName(competitor.getCompetitorName())
                    .evidenceCatalog(slimEvidenceViews(competitor.getEvidenceCatalog()))
                    .structuredEvidence(slimEvidenceViews(competitor.getStructuredEvidence()))
                    .readableEvidence(slimEvidenceViews(competitor.getReadableEvidence()))
                    .skippedEvidence(slimEvidenceViews(competitor.getSkippedEvidence()))
                    .sourceUrls(copyList(competitor.getSourceUrls()))
                    .issueFlags(copyList(competitor.getIssueFlags()))
                    .budget(copyMap(competitor.getBudget()))
                    .build());
        }
        return slimCompetitors;
    }

    private static List<DownstreamEvidenceView> convertAndSlimEvidenceViews(Object rawValue, ObjectMapper objectMapper) {
        if (rawValue == null || objectMapper == null) {
            return List.of();
        }
        List<DownstreamEvidenceView> views = objectMapper.convertValue(
                rawValue,
                new TypeReference<List<DownstreamEvidenceView>>() {
                });
        return slimEvidenceViews(views);
    }

    private static <T> List<T> copyList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(values);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(values);
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = normalizeText(first);
        if (normalizedFirst != null) {
            return normalizedFirst;
        }
        return normalizeText(second);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}

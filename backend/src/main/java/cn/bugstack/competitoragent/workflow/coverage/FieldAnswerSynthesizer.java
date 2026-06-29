package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字段答案合成器。
 * 当前阶段只做字段内结论收口，不跨字段借用证据，后续如需跨字段推理应进入独立 EvidenceGraph/Linker。
 */
@Component
public class FieldAnswerSynthesizer {

    public FieldAnswerConclusion synthesize(String field,
                                            String coverageStatus,
                                            List<String> sourceUrls,
                                            List<String> reasoningSteps,
                                            List<String> contradictions) {
        boolean hasContradiction = contradictions != null && !contradictions.isEmpty();
        String nextAction = hasContradiction ? "REQUIRE_HUMAN_REVIEW" : nextActionFor(coverageStatus);
        return FieldAnswerConclusion.builder()
                .field(field)
                .coverageStatus(coverageStatus)
                .answerValue(answerValueFor(field, coverageStatus))
                .sourceUrls(sourceUrls == null ? List.of() : List.copyOf(sourceUrls))
                .reasoningSteps(reasoningSteps == null ? List.of() : List.copyOf(reasoningSteps))
                .contradictions(contradictions == null ? List.of() : List.copyOf(contradictions))
                .confidence(hasContradiction ? 0.45D : confidenceFor(coverageStatus))
                .recommendedNextAction(nextAction)
                .build();
    }

    private String answerValueFor(String field, String coverageStatus) {
        if ("pricing".equalsIgnoreCase(field) && "CONFIRMED_FREE".equals(coverageStatus)) {
            return "公开证据支持该字段结论：当前能力免费或无独立收费项。";
        }
        if ("NO_PUBLIC_EVIDENCE_AFTER_SEARCH".equals(coverageStatus)) {
            return "";
        }
        return "公开证据支持该字段存在可追溯结论。";
    }

    private String nextActionFor(String coverageStatus) {
        if ("NO_PUBLIC_EVIDENCE_AFTER_SEARCH".equals(coverageStatus)) {
            return "DOWNGRADE_FIELD";
        }
        if ("EVIDENCE_PATH_COVERAGE_NOT_MET".equals(coverageStatus)) {
            return "REPAIR_WITH_TAVILY";
        }
        return "ACCEPT_CONCLUSION";
    }

    private double confidenceFor(String coverageStatus) {
        if ("CONFIRMED_FREE".equals(coverageStatus) || "TRACEABLE".equals(coverageStatus)) {
            return 0.82D;
        }
        if ("IMPLICIT_IN_DOCS".equals(coverageStatus)) {
            return 0.68D;
        }
        return 0.50D;
    }
}

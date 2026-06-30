package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceQueryPlannerGenerativeTest {

    private final FieldEvidenceQueryPlanner planner = new FieldEvidenceQueryPlanner();

    @Test
    void shouldGenerateComplementaryQueriesForFieldWithoutHardcodedBranch() {
        CoverageFieldContract positioning = CoverageFieldContract.builder()
                .field("positioning")
                .status(CoverageFieldStatus.REQUIRED)
                .evidencePaths(List.of(CoverageEvidencePath.builder()
                        .pathKey("OFFICIAL_POSITIONING")
                        .sourceTypes(List.of("OFFICIAL", "REVIEW"))
                        .queryIntents(List.of("BRAND_POSITIONING", "MARKET_SEGMENT"))
                        .expectedSignals(List.of("POSITIONING_BLOCK", "SLOGAN_BLOCK"))
                        .required(true)
                        .build()))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(2)
                .build();

        List<FieldEvidenceQuery> queries = planner.plan("哔哩哔哩", positioning, List.of("bilibili.com"));

        // 生成式 planner 必须让非硬编码字段也获得多视角 query，不能只靠兜底模板拼 intent。
        assertThat(queries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(maxPairwiseTokenOverlap(queries)).isLessThan(0.7);
        assertThat(queries).allSatisfy(q -> assertThat(q.getReason()).isNotBlank());
        assertThat(queries)
                .as("正向字段也必须生成第三方视角 query，官方只是权重而不是门槛")
                .anySatisfy(q -> {
                    assertThat(q.getSourceType()).isIn("REVIEW", "NEWS", "OPEN_WEB");
                    assertThat(q.getQuery()).containsAnyOf("评测", "实测", "对比", "教程", "用户反馈", "解读", "行业分析");
                    assertThat(q.getQuery()).doesNotContain("官方资料");
                });
    }

    private double maxPairwiseTokenOverlap(List<FieldEvidenceQuery> queries) {
        double max = 0;
        for (int i = 0; i < queries.size(); i++) {
            for (int j = i + 1; j < queries.size(); j++) {
                max = Math.max(max, tokenOverlap(queries.get(i).getQuery(), queries.get(j).getQuery()));
            }
        }
        return max;
    }

    private double tokenOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String query) {
        Set<String> tokens = new HashSet<>();
        if (query == null) {
            return tokens;
        }
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s，,]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}

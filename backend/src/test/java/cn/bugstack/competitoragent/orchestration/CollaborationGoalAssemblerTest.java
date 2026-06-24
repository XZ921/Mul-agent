package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationGoalAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CollaborationGoalAssembler assembler = new CollaborationGoalAssembler(objectMapper);

    @Test
    void shouldAssembleGoalFromAnalysisTaskWithBudgetAndConstraints() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .taskName("AI 知识库竞品分析")
                .subjectProduct("企业级 RAG 知识库")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI", "Glean")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so", "https://www.glean.com")))
                .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing", "security")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
                .reportTemplate("标准版")
                .build();

        CollaborationGoal goal = assembler.assemble(task);

        assertThat(goal.getGoalId()).isEqualTo("cg-task-88");
        assertThat(goal.getTaskId()).isEqualTo(88L);
        assertThat(goal.getSubject()).contains("企业级 RAG 知识库");
        assertThat(goal.getCompetitors()).containsExactly("Notion AI", "Glean");
        assertThat(goal.getAnalysisDimensions()).containsExactly("pricing", "security");
        assertThat(goal.getBudget()).containsEntry("maxSearchQueries", 20);
        assertThat(goal.getConstraints()).containsEntry("requireSourceUrls", true);
        assertThat(goal.getSourceUrls()).contains("https://www.notion.so", "https://www.glean.com");
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldKeepMissingSourceStateWhenUserProvidesNoUrls() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(89L)
                .taskName("AI 助手竞品分析")
                .subjectProduct("AI 助手")
                .competitorNames(objectMapper.writeValueAsString(List.of("ChatGPT")))
                .competitorUrls(objectMapper.writeValueAsString(List.of()))
                .analysisDimensions(objectMapper.writeValueAsString(List.of()))
                .build();

        CollaborationGoal goal = assembler.assemble(task);

        assertThat(goal.getAnalysisDimensions()).contains("产品功能", "目标用户", "价格策略");
        assertThat(goal.getSourceUrls()).isEmpty();
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
    }
}

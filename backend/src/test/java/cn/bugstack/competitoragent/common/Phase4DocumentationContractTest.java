package cn.bugstack.competitoragent.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4.8 文档收口契约测试。
 * 这组断言不关心文档排版细节，而是确保第四阶段新增的状态机、对话边界与 Task RAG MVP 能力
 * 已经被明确写进仓库文档，避免代码、界面和阶段说明再次出现各说各话。
 */
class Phase4DocumentationContractTest {

    @Test
    void shouldDescribePhase4InterventionStateMachineAndConversationBoundary() throws IOException {
        String interventionRules = readProjectDocument("phase4-intervention-rules.md");

        // 干预规则文档必须覆盖第四阶段正式引入的新状态与恢复语义。
        assertThat(interventionRules).contains("WAITING_RETRY");
        assertThat(interventionRules).contains("WAITING_INTERVENTION");
        assertThat(interventionRules).contains("COMPENSATED");
        assertThat(interventionRules).contains("DLQ");

        // 同一份文档还要把动态补图、Task RAG 与对话动作边界说清楚，避免工作台和解释入口各讲一套。
        assertThat(interventionRules).contains("动态补图");
        assertThat(interventionRules).contains("Task RAG");
        assertThat(interventionRules).contains("对话");
        assertThat(interventionRules).contains("动作预览");
    }

    @Test
    void shouldDefinePhase4RagAndConversationMvpBoundaryBeforePhase5() throws IOException {
        String phase4Boundary = readProjectDocument("phase4-rag-and-conversation-boundary.md");

        // 第四阶段边界文档必须明确说明当前只是 MVP，不能提前承诺第五阶段的完整能力。
        assertThat(phase4Boundary).contains("Task RAG");
        assertThat(phase4Boundary).contains("MVP");
        assertThat(phase4Boundary).contains("解释型统一入口");
        assertThat(phase4Boundary).contains("Phase 5");
        assertThat(phase4Boundary).contains("完整记忆融合");
        assertThat(phase4Boundary).contains("复杂动作接管");
    }

    /**
     * 文档契约测试直接读取仓库中的最终 Markdown，
     * 这样验证的就是评审、前端和后端共同消费的那份事实来源，而不是测试夹具副本。
     */
    private String readProjectDocument(String fileName) throws IOException {
        Path documentPath = Path.of("..", "docs", fileName);
        return Files.readString(documentPath, StandardCharsets.UTF_8);
    }
}

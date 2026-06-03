package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeExecutionRecoveryPolicyTest {

    private final NodeExecutionRecoveryPolicy recoveryPolicy = new NodeExecutionRecoveryPolicy(new ObjectMapper());

    @Test
    void shouldDeriveStoppedTaskWhenReviewerRequiresHumanIntervention() {
        AnalysisTask task = AnalysisTask.builder()
                .id(1L)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("旧失败状态")
                .build();

        TaskNode writeReport = TaskNode.builder()
                .taskId(1L)
                .nodeName("write_report")
                .displayName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("{\"report\":true}")
                .build();
        TaskNode reviewNode = TaskNode.builder()
                .taskId(1L)
                .nodeName("quality_check")
                .displayName("quality_check")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "passed": false,
                          "requiresHumanIntervention": true,
                          "summary": "需要补证据后再继续"
                        }
                        """)
                .build();
        TaskNode rewriteNode = TaskNode.builder()
                .taskId(1L)
                .nodeName("rewrite_report")
                .displayName("rewrite_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SKIPPED)
                .build();

        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy.resolveTaskExecution(task, List.of(writeReport, reviewNode, rewriteNode));

        assertEquals(AnalysisTaskStatus.STOPPED, resolution.getStatus());
        assertTrue(resolution.getErrorMessage().contains("人工"));
        assertTrue(resolution.isWaitingManualIntervention());
    }

    @Test
    void shouldResetInterruptedNodesButKeepSuccessfulCheckpointOutput() {
        TaskNode successfulCollector = TaskNode.builder()
                .taskId(2L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("{\"node\":\"collect_sources_docs\"}")
                .retryCount(1)
                .build();
        TaskNode runningExtractor = TaskNode.builder()
                .taskId(2L)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.RUNNING)
                .inputData("{\"partial\":true}")
                .outputData("{\"partial\":true}")
                .errorMessage("interrupted")
                .retryCount(2)
                .build();
        TaskNode failedAnalyzer = TaskNode.builder()
                .taskId(2L)
                .nodeName("analyze_competitors")
                .displayName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.FAILED)
                .outputData("{\"failed\":true}")
                .errorMessage("failed")
                .retryCount(3)
                .build();

        boolean recoverable = recoveryPolicy.resetNodesForResume(
                List.of(successfulCollector, runningExtractor, failedAnalyzer),
                true
        );

        assertTrue(recoverable);
        assertEquals(TaskNodeStatus.SUCCESS, successfulCollector.getStatus());
        assertEquals("{\"node\":\"collect_sources_docs\"}", successfulCollector.getOutputData());

        assertEquals(TaskNodeStatus.PENDING, runningExtractor.getStatus());
        assertNull(runningExtractor.getInputData());
        assertNull(runningExtractor.getOutputData());
        assertNull(runningExtractor.getStartedAt());
        assertNull(runningExtractor.getCompletedAt());
        assertEquals(0, runningExtractor.getRetryCount());

        assertEquals(TaskNodeStatus.PENDING, failedAnalyzer.getStatus());
        assertNull(failedAnalyzer.getOutputData());
        assertNull(failedAnalyzer.getErrorMessage());
        assertEquals(0, failedAnalyzer.getRetryCount());
    }
}

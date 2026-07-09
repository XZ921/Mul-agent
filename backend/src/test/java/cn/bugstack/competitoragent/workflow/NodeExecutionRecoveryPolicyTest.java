package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldTreatSuccessDegradedAsTerminalCheckpointDuringResume() {
        TaskNode degradedCollector = TaskNode.builder()
                .taskId(2L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS_DEGRADED)
                .outputData("""
                        {
                          "sourceUrls":["https://docs.example.com"],
                          "degradationReasons":["HARD_DEADLINE_REACHED"]
                        }
                        """)
                .retryCount(1)
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
                List.of(degradedCollector, failedAnalyzer),
                true
        );

        assertTrue(recoverable);
        assertEquals(TaskNodeStatus.SUCCESS_DEGRADED, degradedCollector.getStatus());
        assertTrue(degradedCollector.getOutputData().contains("HARD_DEADLINE_REACHED"));

        assertEquals(TaskNodeStatus.PENDING, failedAnalyzer.getStatus());
        assertNull(failedAnalyzer.getOutputData());
        assertNull(failedAnalyzer.getErrorMessage());
        assertEquals(0, failedAnalyzer.getRetryCount());
    }

    @Test
    void shouldKeepCollectorSearchAuditCheckpointWhenResettingInterruptedNodes() {
        TaskNode runningCollector = TaskNode.builder()
                .taskId(2L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.RUNNING)
                .nodeConfig("""
                        {
                          "searchAuditCheckpoint":{
                            "executionTrace":{"recoveryCheckpoint":"VERIFY_TOP_CANDIDATES"}
                          }
                        }
                        """)
                .outputData("""
                        {
                          "searchAudit":{
                            "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS","degraded":true}
                          }
                        }
                        """)
                .build();

        recoveryPolicy.resetInterruptedNodes(List.of(runningCollector));

        assertEquals(TaskNodeStatus.PENDING, runningCollector.getStatus());
        assertTrue(runningCollector.getNodeConfig().contains("searchAuditCheckpoint"));
        assertTrue(runningCollector.getNodeConfig().contains("SELECT_TARGETS"));
    }

    @Test
    void shouldRespectExplicitNullSearchAuditCheckpointWhenResettingInterruptedCollector() throws Exception {
        TaskNode runningCollector = TaskNode.builder()
                .taskId(3L)
                .nodeName("collect_sources_news")
                .displayName("collect_sources_news")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.RUNNING)
                .nodeConfig("""
                        {
                          "competitorName":"RSS Smoke",
                          "sourceType":"NEWS",
                          "searchAuditCheckpoint":null,
                          "collectionAuditCheckpoint":null
                        }
                        """)
                .outputData("""
                        {
                          "searchAudit":{
                            "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS","degraded":true}
                          }
                        }
                        """)
                .build();

        recoveryPolicy.resetInterruptedNodes(List.of(runningCollector));

        JsonNode updatedConfig = new ObjectMapper().readTree(runningCollector.getNodeConfig());
        assertEquals(TaskNodeStatus.PENDING, runningCollector.getStatus());
        assertTrue(updatedConfig.has("searchAuditCheckpoint"));
        assertTrue(updatedConfig.path("searchAuditCheckpoint").isNull());
        assertTrue(updatedConfig.has("collectionAuditCheckpoint"));
        assertTrue(updatedConfig.path("collectionAuditCheckpoint").isNull());
    }

    @Test
    void shouldNotStopTaskWhenOnlyCollectorWaitsForInterventionButDownstreamCanContinue() {
        AnalysisTask task = AnalysisTask.builder()
                .id(4L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();

        TaskNode degradedCollector = TaskNode.builder()
                .taskId(4L)
                .nodeName("collect_sources_pricing")
                .displayName("collect_sources_pricing")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .failureCategory(NodeFailureCategory.DEADLINE_EXHAUSTED)
                .outputData("""
                        {
                          "degradationReasons":["HARD_DEADLINE_REACHED"]
                        }
                        """)
                .build();
        TaskNode extractor = TaskNode.builder()
                .taskId(4L)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.READY)
                .required(true)
                .build();

        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy.resolveTaskExecution(task, List.of(degradedCollector, extractor));

        assertEquals(AnalysisTaskStatus.RUNNING, resolution.getStatus());
        assertTrue(recoveryPolicy.canAutoContinue(List.of(degradedCollector, extractor)));
    }

    @Test
    void shouldStopTaskWhenCollectorWaitsForInterventionBecauseRetryWasExhausted() {
        AnalysisTask task = AnalysisTask.builder()
                .id(5L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();

        TaskNode blockedCollector = TaskNode.builder()
                .taskId(5L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .failureCategory(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE)
                .errorMessage("自动重试次数已耗尽，等待人工决定是否继续")
                .build();
        TaskNode analyzer = TaskNode.builder()
                .taskId(5L)
                .nodeName("analyze_competitors")
                .displayName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.SUCCESS)
                .required(true)
                .build();

        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy.resolveTaskExecution(task, List.of(blockedCollector, analyzer));

        assertEquals(AnalysisTaskStatus.STOPPED, resolution.getStatus());
        assertTrue(resolution.isWaitingManualIntervention());
        assertFalse(recoveryPolicy.canAutoContinue(List.of(blockedCollector, analyzer)));
    }

    @Test
    void shouldResolveSuccessWhenReviewOnlyContainsDeferredStageOneIssues() {
        AnalysisTask task = AnalysisTask.builder()
                .id(6L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();

        TaskNode writerNode = TaskNode.builder()
                .taskId(6L)
                .nodeName("write_report")
                .displayName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "content":"# Stage1 Report",
                          "sourceUrls":[
                            "https://www.notion.so/product/ai",
                            "https://www.notion.so/security",
                            "https://docs.notion.so/ai",
                            "https://docs.notion.so/admins",
                            "https://www.g2.com/products/notion-ai/reviews"
                          ]
                        }
                        """)
                .build();
        TaskNode reviewNode = TaskNode.builder()
                .taskId(6L)
                .nodeName("quality_check")
                .displayName("quality_check")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "passed": false,
                          "requiresHumanIntervention": true,
                          "diagnoses": [
                            {
                              "type":"MISSING_CITATION",
                              "section":"定价策略",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "evidenceBasis":"pricing 仍需补齐逐句引用"
                            },
                            {
                              "type":"MISSING_CITATION",
                              "section":"report_conclusion",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "evidenceBasis":"report_conclusion 仍需保守改写"
                            }
                          ]
                        }
                        """)
                .build();
        TaskNode rewriteNode = TaskNode.builder()
                .taskId(6L)
                .nodeName("rewrite_report")
                .displayName("rewrite_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SKIPPED)
                .build();

        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy.resolveTaskExecution(task, List.of(writerNode, reviewNode, rewriteNode));

        assertEquals(AnalysisTaskStatus.SUCCESS, resolution.getStatus());
        assertFalse(resolution.isWaitingManualIntervention());
        assertNull(resolution.getErrorMessage());
    }
}

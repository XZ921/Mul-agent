package cn.bugstack.competitoragent.event;

import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * TaskEventReplayService 的测试重点不是 SSE 通道本身，
 * 而是断线恢复时能否稳定产出“快照优先、游标补偿其次”的最小恢复计划。
 */
@ExtendWith(MockitoExtension.class)
class TaskEventReplayServiceTest {

    @Mock
    private TaskRecoveryService taskRecoveryService;

    private TaskSseHub taskSseHub;
    private TaskEventReplayService replayService;

    @BeforeEach
    void setUp() {
        taskSseHub = new TaskSseHub();
        replayService = new TaskEventReplayService(taskSseHub, taskRecoveryService);
    }

    @Test
    void shouldRecoverSnapshotBeforeReplayingEventsAfterCursor() {
        TaskProgressSnapshot snapshot = TaskProgressSnapshot.builder()
                .taskId(24L)
                .taskStatus("RUNNING")
                .currentStage("多源补源")
                .completedNodes(2)
                .totalNodes(6)
                .activeNodeNames(List.of("collect_sources_01_01"))
                .updatedAt(LocalDateTime.of(2026, 6, 3, 19, 0, 0))
                .build();
        when(taskRecoveryService.getTaskSnapshotOrRebuild(24L)).thenReturn(Optional.of(snapshot));

        TaskEventPublisher publisher = new TaskEventPublisher(taskSseHub);
        TaskStreamEvent first = publisher.publishTaskStatusEvent(24L, AnalysisTaskStatus.RUNNING, "信息采集", null);
        TaskStreamEvent second = publisher.publishSearchProgressEvent(24L, "collect_sources_01_01", Map.of(
                "nodeName", "collect_sources_01_01",
                "searchProgress", Map.of("status", "RUNNING", "currentStep", "搜索候选来源")));
        TaskStreamEvent third = publisher.publishDiagnosisEvent(24L, "quality_check", Map.of(
                "summary", "证据仍需补强",
                "requiresHumanIntervention", true));

        TaskEventReplayService.TaskReplayFrame frame = replayService.planReplay(24L, first.getCursor());

        assertNotNull(frame);
        assertNotNull(frame.getSnapshotEvent());
        assertEquals(TaskEventType.TASK_SNAPSHOT, frame.getSnapshotEvent().getEventType());
        assertNull(frame.getSnapshotEvent().getCursor());
        assertEquals("RUNNING", frame.getSnapshotEvent().getPayload().get("status"));
        assertEquals(2, frame.getReplayEvents().size());
        assertEquals(second.getCursor(), frame.getReplayEvents().get(0).getCursor());
        assertEquals(third.getCursor(), frame.getReplayEvents().get(1).getCursor());
        assertEquals(first.getCursor(), frame.getResumeCursor());
    }

    @Test
    void shouldIgnoreMalformedOrForeignCursorDuringReplayPlanning() {
        TaskEventPublisher publisher = new TaskEventPublisher(taskSseHub);
        publisher.publishTaskStatusEvent(24L, AnalysisTaskStatus.RUNNING, "信息采集", null);
        publisher.publishTaskStatusEvent(24L, AnalysisTaskStatus.SUCCESS, "执行完成", null);

        when(taskRecoveryService.getTaskSnapshotOrRebuild(24L)).thenReturn(Optional.empty());

        TaskEventReplayService.TaskReplayFrame malformedCursorFrame = replayService.planReplay(24L, "bad-cursor");
        TaskEventReplayService.TaskReplayFrame foreignCursorFrame = replayService.planReplay(24L, "25-9");

        assertFalse(malformedCursorFrame.getReplayEvents().isEmpty());
        assertFalse(foreignCursorFrame.getReplayEvents().isEmpty());
        assertNull(malformedCursorFrame.getResumeCursor());
        assertNull(foreignCursorFrame.getResumeCursor());
    }

    @Test
    void shouldParseComparableTaskEventCursor() {
        TaskEventCursor earlier = TaskEventCursor.parse("24-7").orElseThrow();
        TaskEventCursor later = TaskEventCursor.parse("24-12").orElseThrow();

        assertTrue(later.isAfter(earlier));
        assertFalse(earlier.isAfter(later));
        assertEquals(24L, later.taskId());
        assertEquals(12L, later.sequence());
    }
}

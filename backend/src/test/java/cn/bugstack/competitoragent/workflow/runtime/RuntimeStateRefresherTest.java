package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeStateRefresherTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final TaskSnapshotCacheService taskSnapshotCacheService = mock(TaskSnapshotCacheService.class);
    private final TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
    private final RuntimeStateRefresher refresher = new RuntimeStateRefresher(
            taskRepository,
            nodeRepository,
            taskSnapshotCacheService,
            taskEventPublisher
    );

    @Test
    void shouldDeriveRunningSnapshotWhenNodeAlreadyRunningButTaskStillPending() {
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode runningNode = TaskNode.builder()
                .taskId(88L)
                .nodeName("analyze_competitors")
                .displayName("分析竞品")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.RUNNING)
                .required(true)
                .build();

        when(taskRepository.findById(88L)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(88L)).thenReturn(List.of(runningNode));

        refresher.refreshRuntimeSnapshot(88L);

        ArgumentCaptor<TaskProgressSnapshot> snapshotCaptor = ArgumentCaptor.forClass(TaskProgressSnapshot.class);
        verify(taskSnapshotCacheService).saveTaskSnapshot(snapshotCaptor.capture());
        verify(taskEventPublisher).publishTaskSnapshot(snapshotCaptor.getValue());
        assertThat(snapshotCaptor.getValue().getTaskStatus()).isEqualTo("RUNNING");
        assertThat(snapshotCaptor.getValue().getActiveNodeNames()).containsExactly("analyze_competitors");
    }
}

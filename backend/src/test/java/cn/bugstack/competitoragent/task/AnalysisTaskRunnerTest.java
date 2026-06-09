package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.workflow.DagExecutor;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskRunnerTest {

    @Mock
    private AnalysisTaskRepository taskRepository;

    @Mock
    private DagExecutor dagExecutor;

    @Mock
    private TaskExecutionLockService taskExecutionLockService;

    @Mock
    private TaskEventPublisher taskEventPublisher;

    @Mock
    private WorkflowEventPublisher workflowEventPublisher;

    @InjectMocks
    private AnalysisTaskRunner analysisTaskRunner;

    @Test
    void shouldStageWorkflowDispatchEventInsteadOfExecutingDagDirectly() {
        AnalysisTask task = AnalysisTask.builder()
                .id(9L)
                .taskName("workflow-event-entry")
                .subjectProduct("Workspace")
                .competitorNames("[\"Notion AI\"]")
                .competitorUrls("[\"https://www.notion.so\"]")
                .analysisDimensions("[\"pricing\"]")
                .sourceScope("[\"官网\"]")
                .reportLanguage("中文")
                .reportTemplate("标准版")
                .build();

        when(taskExecutionLockService.tryAcquireTaskExecutionLock(eq(9L), any(), any(Duration.class))).thenReturn(true);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        analysisTaskRunner.runTask(9L);

        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(workflowEventPublisher).publishTaskExecutionRequested(eq(task), contextCaptor.capture());
        verify(dagExecutor, never()).execute(any(), any());
        verify(taskExecutionLockService).releaseTaskExecutionLock(eq(9L), any());

        AgentContext context = contextCaptor.getValue();
        assertThat(context.getTaskId()).isEqualTo(9L);
        assertThat(context.getTaskName()).isEqualTo("workflow-event-entry");
        assertThat(context.getCompetitorNames()).contains("Notion AI");
        assertThat(context.getCompetitorUrls()).contains("https://www.notion.so");
        assertThat(context.getAnalysisDimensions()).contains("pricing");
        assertThat(context.getSourceScope()).contains("官网");
    }
}

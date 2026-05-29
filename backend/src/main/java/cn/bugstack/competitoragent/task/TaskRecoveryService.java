package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务恢复服务。
 * 在服务启动时扫描处于 RUNNING 的任务，把中途中断的 RUNNING 节点回滚为 PENDING，再自动按已有成功节点恢复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final AnalysisTaskRunner taskRunner;

    @Component
    @RequiredArgsConstructor
    static class RecoveryBootstrap implements ApplicationRunner {

        private final TaskRecoveryService recoveryService;

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            recoveryService.recoverInterruptedTasks();
        }
    }

    /**
     * 启动时恢复所有被异常中断的任务。
     */
    @Transactional
    public void recoverInterruptedTasks() {
        List<AnalysisTask> runningTasks = taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING);
        if (runningTasks.isEmpty()) {
            return;
        }

        for (AnalysisTask task : runningTasks) {
            boolean recoverable = resetInterruptedNodes(task.getId());
            if (!recoverable) {
                task.setStatus(AnalysisTaskStatus.FAILED);
                task.setCompletedAt(LocalDateTime.now());
                task.setErrorMessage("Task interrupted and cannot be resumed because no workflow nodes were found");
                taskRepository.save(task);
                continue;
            }

            task.setStatus(AnalysisTaskStatus.PENDING);
            task.setCompletedAt(null);
            task.setErrorMessage("Recovered after service restart, resuming from node checkpoints");
            taskRepository.save(task);
            runAfterCommit(task.getId());
            log.info("schedule interrupted task recovery, taskId={}", task.getId());
        }
    }

    /**
     * 只回滚中断时仍停留在 RUNNING/PENDING 的节点，保留 SUCCESS 检查点供执行器续跑。
     */
    @Transactional
    public boolean resetInterruptedNodes(Long taskId) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (TaskNode node : nodes) {
            TaskNodeStatus originalStatus = node.getStatus();
            if (originalStatus == TaskNodeStatus.RUNNING || originalStatus == TaskNodeStatus.PENDING) {
                node.setStatus(TaskNodeStatus.PENDING);
                node.setInputData(null);
                node.setOutputData(originalStatus == TaskNodeStatus.RUNNING ? null : node.getOutputData());
                node.setErrorMessage(originalStatus == TaskNodeStatus.RUNNING
                        ? "Node interrupted by service restart"
                        : node.getErrorMessage());
                node.setStartedAt(null);
                node.setCompletedAt(null);
                node.setRetryCount(0);
                changed = true;
            }
        }

        if (changed) {
            nodeRepository.saveAll(nodes);
        }
        return true;
    }

    private void runAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskRunner.runTask(taskId);
                }
            });
        } else {
            taskRunner.runTask(taskId);
        }
    }
}

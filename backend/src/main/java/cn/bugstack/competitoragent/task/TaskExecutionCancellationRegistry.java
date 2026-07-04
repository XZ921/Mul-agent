package cn.bugstack.competitoragent.task;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 任务运行期取消句柄注册表。
 * <p>
 * stop 命令需要同时触达两类运行时资源：
 * 1. 任务级线程池，避免主编排循环继续阻塞等待；
 * 2. 节点级 future，尽快向正在执行的节点传播中断信号。
 * <p>
 * 这里不承载业务状态，只保存短生命周期句柄；真正的权威状态仍然由数据库中的 task/node 记录决定。
 */
@Component
public class TaskExecutionCancellationRegistry {

    private final Map<Long, ExecutorService> taskExecutors = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Future<?>>> taskNodeFutures = new ConcurrentHashMap<>();

    public void registerTaskExecutor(Long taskId, ExecutorService executorService) {
        if (taskId == null || executorService == null) {
            return;
        }
        taskExecutors.put(taskId, executorService);
    }

    public void registerNodeFuture(Long taskId, String nodeName, Future<?> future) {
        if (taskId == null || nodeName == null || nodeName.isBlank() || future == null) {
            return;
        }
        taskNodeFutures.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .put(nodeName, future);
    }

    public void cancelTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        Map<String, Future<?>> nodeFutures = taskNodeFutures.remove(taskId);
        if (nodeFutures != null) {
            for (Future<?> future : nodeFutures.values()) {
                if (future != null) {
                    future.cancel(true);
                }
            }
        }
        ExecutorService executorService = taskExecutors.remove(taskId);
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    public void clearTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        taskNodeFutures.remove(taskId);
        taskExecutors.remove(taskId);
    }
}

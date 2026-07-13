package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalysisStatusService {

    private final AgentCheckpointService checkpointService;
    private final AnalysisDispatchService dispatchService;

    public AnalysisStatusService(AgentCheckpointService checkpointService,
                                 AnalysisDispatchService dispatchService) {
        this.checkpointService = checkpointService;
        this.dispatchService = dispatchService;
    }

    public TaskStatus current(Long mediaId, String goal) {
        AgentState result = checkpointService.loadResult(mediaId, goal);
        if (result != null && result.result() != null) {
            return TaskStatus.completed(result.result().toMarkdown());
        }

        TaskStage stage = checkpointService.loadStage(mediaId, goal);
        if (dispatchService.isActive(mediaId, goal)) {
            TaskStatus.State state = stage == null ? TaskStatus.State.QUEUED : TaskStatus.State.PROCESSING;
            return TaskStatus.of(state, state == TaskStatus.State.QUEUED ? "任务已排队" : "正在分析");
        }
        if (stage == TaskStage.FAILED || stage == TaskStage.DEAD_LETTERED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "分析失败，请稍后重试");
        }
        return TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交分析任务");
    }

    public TaskStage stage(Long mediaId, String goal) {
        return checkpointService.loadStage(mediaId, goal);
    }
}

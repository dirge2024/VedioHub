package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AnalysisStatusService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AiService;
import com.example.server.service.AuthService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private static final int MAX_GOAL_LENGTH = 500;

    private final AiService aiService;
    private final AnalysisDispatchService dispatchService;
    private final AgentCheckpointService checkpointService;
    private final AgentEvaluationService evaluationService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final AnalysisStatusService statusService;

    public AnalysisController(AiService aiService,
                              AnalysisDispatchService dispatchService,
                              AgentCheckpointService checkpointService,
                              AgentEvaluationService evaluationService,
                              AgentTelemetry telemetry,
                              MediaService mediaService,
                              TaskEventService taskEventService,
                              AnalysisStatusService statusService) {
        this.aiService = aiService;
        this.dispatchService = dispatchService;
        this.checkpointService = checkpointService;
        this.evaluationService = evaluationService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.statusService = statusService;
    }

    @PostMapping("/ai")
    public ResponseEntity<Result<Void>> aiAnalyze(
            @RequestParam Long id,
            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedGoal = normalizeText(goal, "分析目标");
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if (checkpointService.loadResult(id, normalizedGoal) != null) {
            // 已有可复用结果，是“已完成”而非“已受理”，用 200 与异步受理区分开。
            return ResponseEntity.ok(Result.ok());
        }
        return submissionResponse(dispatchService.submit(mediaFile, normalizedGoal, null));
    }

    @PostMapping("/follow-up")
    public Result<String> followUp(
            @RequestParam Long id,
            @RequestParam String question,
            @RequestParam(required = false) String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        String normalizedQuestion = normalizeText(question, "追问内容");
        String normalizedGoal = goal == null || goal.isBlank()
                ? null : normalizeText(goal, "原始分析目标");
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(aiService.followUp(id, normalizedGoal, normalizedQuestion));
    }

    @GetMapping("/evidence-search")
    public Result<List<VideoEvidenceHit>> searchEvidence(
            @RequestParam Long id,
            @RequestParam String query,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(aiService.searchEvidence(id, normalizeText(query, "检索问题")));
    }

    @PostMapping("/agent-feedback")
    public Result<Void> agentFeedback(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        checkpointService.saveFeedback(feedback.normalized());
        return Result.ok();
    }

    @PostMapping("/agent-revise")
    public ResponseEntity<Result<Void>> reviseAgentResult(
            @Valid @RequestBody AgentFeedback feedback,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        ensureRating(feedback);
        MediaFile mediaFile = mediaService.requireOwnedMedia(feedback.mediaId(), userId);
        String revisedGoal = aiService.revisionGoal(feedback);
        return submissionResponse(dispatchService.submit(mediaFile, revisedGoal, feedback));
    }

    @GetMapping("/agent-feedback")
    public Result<List<AgentFeedback>> agentFeedback(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadFeedback(id));
    }

    @GetMapping("/agent-plan")
    public Result<AgentState.AgentPlan> agentPlan(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(checkpointService.loadPlan(id, normalizeText(goal, "分析目标")));
    }

    @GetMapping("/analysis-status")
    public Result<TaskStatus> analysisStatus(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        return Result.ok(statusService.current(id, normalizedGoal));
    }

    @GetMapping(value = "/analysis-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analysisEvents(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        String normalizedGoal = normalizeText(goal, "分析目标");
        return taskEventService.subscribe(
                id,
                TaskEventService.ANALYSIS,
                normalizedGoal,
                statusService.current(id, normalizedGoal),
                statusService.stage(id, normalizedGoal));
    }

    @GetMapping("/agent-evaluation")
    public Result<Map<String, Object>> agentEvaluation(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(evaluationService.evaluate(id, normalizeText(goal, "分析目标")));
    }

    @GetMapping("/agent-trace")
    public Result<Map<String, Object>> agentTrace(
            @RequestParam Long id,
            @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return Result.ok(telemetry.latest(id, normalizeText(goal, "分析目标")));
    }

    /**
     * 请求参数的规整 + 校验：既做 trim 归一（结果参与幂等 key 计算，不能省），
     * 又限制长度。请求体（DTO）改用 Bean Validation，参数这里保留是因为它承担了归一职责。
     */
    private String normalizeText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException(field + "不能为空且不能超过 " + MAX_GOAL_LENGTH + " 字");
        }
        return value.trim();
    }

    private void ensureRating(AgentFeedback feedback) {
        Integer rating = feedback.rating();
        if (rating != null && rating != -1 && rating != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "rating 只能是 -1 或 1");
        }
    }

    /**
     * 异步任务受理返回 202 Accepted：明确告诉客户端“已接单、结果稍后轮询/订阅”，
     * 与同步完成的 200 区分开，便于前端区分“已完成”“已受理”“需重试”三种状态。
     */
    private ResponseEntity<Result<Void>> submissionResponse(
            AnalysisDispatchService.SubmissionResult result) {
        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted().body(Result.ok());
            case RATE_LIMITED -> throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
            case DUPLICATE -> throw new BusinessException(ErrorCode.CONFLICT, "相同视频和分析目标正在处理中");
            case FAILED -> throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务提交失败");
        };
    }
}

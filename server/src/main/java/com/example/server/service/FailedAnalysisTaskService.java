package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class FailedAnalysisTaskService {

    private static final Logger log = LoggerFactory.getLogger(FailedAnalysisTaskService.class);
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REQUEUED = "REQUEUED";
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_ ]?key|token|secret)\\s*[=:]\\s*)[^\\s,;]{8,}");
    private static final Pattern PREFIXED_SECRET = Pattern.compile(
            "(?i)sk-[A-Za-z0-9_-]{16,}");

    private final FailedAnalysisTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;
    private final String analysisTopic;

    public FailedAnalysisTaskService(FailedAnalysisTaskMapper taskMapper,
                                     RocketMQTemplate rocketMQTemplate,
                                     StringRedisTemplate redisTemplate,
                                     TaskEventService taskEventService,
                                     @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                     String analysisTopic) {
        this.taskMapper = taskMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
        this.analysisTopic = analysisTopic;
    }

    public void record(AnalysisTaskMsg message, long attempts, Throwable error) {
        Throwable root = rootCause(error);
        FailedAnalysisTask task = new FailedAnalysisTask();
        task.setMediaId(message.getMediaId());
        task.setAction(message.getAction());
        task.setContentHash(message.getContentHash());
        task.setUserGoal(message.getUserGoal());
        task.setAttemptCount((int) attempts);
        task.setErrorType(root.getClass().getSimpleName());
        task.setErrorMessage(sanitizeError(root.getMessage()));
        task.setStatus(STATUS_FAILED);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
    }

    public List<FailedAnalysisTask> latest() {
        return taskMapper.selectList(new QueryWrapper<FailedAnalysisTask>()
                .orderByDesc("id")
                .last("LIMIT 100"));
    }

    public void replay(Long id) {
        FailedAnalysisTask task = taskMapper.selectById(id);
        if (task == null) throw new NoSuchElementException("失败任务不存在");
        if (!STATUS_FAILED.equals(task.getStatus())) {
            throw new IllegalArgumentException("该失败任务已经重放");
        }

        String contentHash = AnalysisTaskKeys.normalizeContentHash(task.getMediaId(), task.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(task.getUserGoal());
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(task.getMediaId()), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) throw new IllegalArgumentException("相同任务正在处理中");

        boolean dispatched = false;
        try {
            redisTemplate.delete(AnalysisTaskKeys.attempts(contentHash, goalDigest));
            rocketMQTemplate.convertAndSend(analysisTopic, new AnalysisTaskMsg(
                    task.getMediaId(), task.getAction(), contentHash, task.getUserGoal()));
            dispatched = true;
            task.setStatus(STATUS_REQUEUED);
            task.setUpdatedAt(LocalDateTime.now());
            if (taskMapper.updateById(task) != 1) {
                throw new IllegalStateException("失败任务重放台账更新失败");
            }
        } catch (RuntimeException e) {
            if (!dispatched) {
                redisTemplate.delete(activeKey);
            } else {
                // 消息已发出时保留幂等键，避免台账更新失败诱发重复重放。
                log.error("failed_analysis_replay_bookkeeping_failed taskId={} mediaId={}",
                        id, task.getMediaId(), e);
            }
            throw e;
        }

        try {
            taskEventService.publishAnalysis(task.getMediaId(), task.getUserGoal(),
                    TaskStatus.of(TaskStatus.State.QUEUED, "失败任务已由管理员重新入队"),
                    TaskStage.MANUAL_REPLAY);
        } catch (RuntimeException eventError) {
            log.warn("failed_analysis_replay_event_failed taskId={} mediaId={}",
                    id, task.getMediaId(), eventError);
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String sanitizeError(String value) {
        if (value == null) return null;
        String sanitized = BEARER_SECRET.matcher(value).replaceAll("$1****");
        sanitized = NAMED_SECRET.matcher(sanitized).replaceAll("$1****");
        sanitized = PREFIXED_SECRET.matcher(sanitized).replaceAll("****");
        return truncate(sanitized, 1_000);
    }
}

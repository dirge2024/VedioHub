package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.repository.AgentCheckpointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 对外只暴露 Agent 领域里的计划、上下文、Critic 和结果 Checkpoint。 */
@Service
public class AgentCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpointService.class);
    private static final int MAX_FEEDBACK_SAMPLES = 200;
    private static final Duration REVISION_TTL = Duration.ofHours(2);
    private static final Duration FEEDBACK_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointRepository checkpointRepository;

    public AgentCheckpointService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  AgentCheckpointRepository checkpointRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointRepository = checkpointRepository;
    }

    public VideoContext loadContext(Long mediaId) {
        return checkpointRepository.read(mediaId, mediaCheckpoint("context"), checkpointKey(mediaId),
                "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, "result"), goalKey(mediaId, goal),
                "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, "plan"), goalKey(mediaId, goal),
                "plan", AgentState.AgentPlan.class);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return checkpointRepository.read(mediaId, goalCheckpoint(goal, "criticState"), goalKey(mediaId, goal),
                "criticState", AgentState.class);
    }

    public TaskStage loadStage(Long mediaId, String goal) {
        return checkpointRepository.readStage(
                mediaId, goalCheckpoint(goal, "stage"), goalKey(mediaId, goal));
    }

    public List<VideoChunk> loadChunks(Long mediaId) {
        return checkpointRepository.read(
                mediaId,
                mediaCheckpoint("chunks"),
                checkpointKey(mediaId),
                "chunks",
                new TypeReference<List<VideoChunk>>() { });
    }

    public void saveContext(Long mediaId, VideoContext context) {
        VideoContext reusableContext = new VideoContext(context.source(), "", context.segments());
        checkpointRepository.write(mediaId, mediaCheckpoint("context"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "context", TaskStage.CONTEXT_COMPLETED, reusableContext);
    }

    public void saveChunks(Long mediaId, List<VideoChunk> chunks) {
        checkpointRepository.write(mediaId, mediaCheckpoint("chunks"), mediaCheckpoint("stage"),
                checkpointKey(mediaId), "chunks", TaskStage.CHUNKS_COMPLETED, List.copyOf(chunks));
    }

    public void saveResult(Long mediaId, AgentState state) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.ANALYSIS_COMPLETED : TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
        String key = goalKey(mediaId, state.goal());
        checkpointRepository.write(mediaId, goalCheckpoint(state.goal(), "result"), goalCheckpoint(state.goal(), "stage"),
                key, "result", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = goalKey(mediaId, goal);
        checkpointRepository.write(mediaId, goalCheckpoint(goal, "plan"), goalCheckpoint(goal, "stage"),
                key, "plan", TaskStage.PLAN_COMPLETED, plan);
        rememberGoalKey(mediaId, key);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        TaskStage stage = state.critique() != null && state.critique().passed()
                ? TaskStage.CRITIC_PASSED : TaskStage.CRITIC_RETRY_REQUIRED;
        String key = goalKey(mediaId, state.goal());
        checkpointRepository.write(mediaId, goalCheckpoint(state.goal(), "criticState"), goalCheckpoint(state.goal(), "stage"),
                key, "criticState", stage, state);
        rememberGoalKey(mediaId, key);
    }

    public void stageRevision(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = revisionKey(mediaId, goal);
        try {
            redisTemplate.opsForHash().put(key, "pending", "1");
            if (plan != null) {
                redisTemplate.opsForHash().put(key, "plan", objectMapper.writeValueAsString(plan));
            }
            redisTemplate.expire(key, REVISION_TTL);
            rememberGoalKey(mediaId, key);
        } catch (Exception e) {
            throw new IllegalStateException("暂存 Agent 修正计划失败", e);
        }
    }

    public boolean beginStagedRevision(Long mediaId, String goal) {
        String revisionKey = revisionKey(mediaId, goal);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(revisionKey))) return false;

        AgentState.AgentPlan plan = readRevisionPlan(revisionKey);
        checkpointRepository.deleteByPrefix(mediaId, goalCheckpoint(goal, ""));
        redisTemplate.delete(goalKey(mediaId, goal));
        if (plan != null) savePlan(mediaId, goal, plan);
        redisTemplate.delete(revisionKey);
        return true;
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        redisTemplate.delete(revisionKey(mediaId, goal));
    }

    public void saveFeedback(AgentFeedback feedback) {
        try {
            redisTemplate.opsForList().rightPush(
                    feedbackKey(feedback.mediaId()), objectMapper.writeValueAsString(feedback.normalized()));
            redisTemplate.opsForList().trim(feedbackKey(feedback.mediaId()), -MAX_FEEDBACK_SAMPLES, -1);
            redisTemplate.expire(feedbackKey(feedback.mediaId()), FEEDBACK_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent 用户反馈失败", e);
        }
    }

    public List<AgentFeedback> loadFeedback(Long mediaId) {
        List<String> values = redisTemplate.opsForList().range(feedbackKey(mediaId), 0, -1);
        if (values == null) return List.of();
        return values.stream().map(value -> {
            try {
                return objectMapper.readValue(value, AgentFeedback.class);
            } catch (Exception e) {
                log.warn("agent_feedback_deserialize_failed mediaId={}", mediaId, e);
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    public void saveFailure(Long mediaId, String goal, TaskStage failedStage, Exception error) {
        String key = goalKey(mediaId, goal);
        checkpointRepository.writeStage(
                mediaId, goalCheckpoint(goal, "stage"), key, TaskStage.FAILED);
        redisTemplate.opsForHash().put(key, "failedStage", failedStage.name());
        redisTemplate.opsForHash().put(key, "errorType", error.getClass().getSimpleName());
        redisTemplate.expire(key, Duration.ofDays(7));
        rememberGoalKey(mediaId, key);
    }

    public void deleteMedia(Long mediaId) {
        checkpointRepository.deleteByMediaId(mediaId);
        try {
            Set<String> goalKeys = redisTemplate.opsForSet().members(goalIndexKey(mediaId));
            List<String> keys = new ArrayList<>();
            keys.add(checkpointKey(mediaId));
            keys.add(feedbackKey(mediaId));
            keys.add(goalIndexKey(mediaId));
            if (goalKeys != null) keys.addAll(goalKeys);
            redisTemplate.delete(keys);
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_cache_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private AgentState.AgentPlan readRevisionPlan(String key) {
        try {
            Object value = redisTemplate.opsForHash().get(key, "plan");
            return value == null ? null : objectMapper.readValue(
                    value.toString(), AgentState.AgentPlan.class);
        } catch (Exception e) {
            log.warn("agent_revision_plan_read_failed key={}", key, e);
            return null;
        }
    }

    private void rememberGoalKey(Long mediaId, String key) {
        try {
            redisTemplate.opsForSet().add(goalIndexKey(mediaId), key);
            redisTemplate.expire(goalIndexKey(mediaId), Duration.ofDays(7));
        } catch (RuntimeException e) {
            log.warn("agent_checkpoint_index_write_failed mediaId={} key={}", mediaId, key, e);
        }
    }

    private String checkpointKey(Long mediaId) {
        return "agent:checkpoint:" + mediaId;
    }

    private String goalKey(Long mediaId, String goal) {
        return checkpointKey(mediaId) + ":goal:" + AnalysisTaskKeys.goalDigest(goal);
    }

    private String feedbackKey(Long mediaId) {
        return "agent:feedback:" + mediaId;
    }

    private String revisionKey(Long mediaId, String goal) {
        return goalKey(mediaId, goal) + ":revision";
    }

    private String goalIndexKey(Long mediaId) {
        return checkpointKey(mediaId) + ":goals";
    }

    private String mediaCheckpoint(String field) {
        return "media:" + field;
    }

    private String goalCheckpoint(String goal, String field) {
        return "goal:" + AnalysisTaskKeys.goalDigest(goal) + ":" + field;
    }
}

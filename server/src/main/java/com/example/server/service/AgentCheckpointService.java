package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentCheckpointService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public VideoContext loadContext(Long mediaId) {
        return read(checkpointKey(mediaId), "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "plan", AgentState.AgentPlan.class);
    }

    public AgentState loadCriticState(Long mediaId, String goal) {
        return read(goalKey(mediaId, goal), "criticState", AgentState.class);
    }

    public void saveContext(Long mediaId, VideoContext context) {
        write(checkpointKey(mediaId), "context", "CONTEXT_COMPLETED", context);
    }

    public void saveResult(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "ANALYSIS_COMPLETED" : "ANALYSIS_COMPLETED_WITH_WARNINGS";
        write(goalKey(mediaId, state.goal()), "result", stage, state);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        write(goalKey(mediaId, goal), "plan", "PLAN_COMPLETED", plan);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        String stage = state.critique() != null && state.critique().passed()
                ? "CRITIC_PASSED" : "CRITIC_RETRY_REQUIRED";
        write(goalKey(mediaId, state.goal()), "criticState", stage, state);
    }

    public void resetForRerun(Long mediaId, String goal, AgentState.AgentPlan plan) {
        String key = goalKey(mediaId, goal);
        redisTemplate.delete(key);
        if (plan != null) savePlan(mediaId, goal, plan);
    }

    public void saveFeedback(AgentFeedback feedback) {
        try {
            redisTemplate.opsForList().rightPush(
                    feedbackKey(feedback.mediaId()), objectMapper.writeValueAsString(feedback.normalized()));
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent 用户反馈失败", e);
        }
    }

    public List<AgentFeedback> loadFeedback(Long mediaId) {
        try {
            List<String> values = redisTemplate.opsForList().range(feedbackKey(mediaId), 0, -1);
            if (values == null) return List.of();
            return values.stream().map(value -> {
                try {
                    return objectMapper.readValue(value, AgentFeedback.class);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        } catch (Exception e) {
            throw new IllegalStateException("读取 Agent 用户反馈失败", e);
        }
    }

    public void saveFailure(Long mediaId, String goal, String failedStage, Exception error) {
        String key = goalKey(mediaId, goal);
        redisTemplate.opsForHash().put(key, "stage", "FAILED");
        redisTemplate.opsForHash().put(key, "failedStage", failedStage);
        redisTemplate.opsForHash().put(key, "error", String.valueOf(error.getMessage()));
    }

    private <T> T read(String key, String field, Class<T> type) {
        try {
            Object value = redisTemplate.opsForHash().get(key, field);
            return value == null ? null : objectMapper.readValue(value.toString(), type);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Agent Checkpoint 失败", e);
        }
    }

    private void write(String key, String field, String stage, Object value) {
        try {
            redisTemplate.opsForHash().put(key, field, objectMapper.writeValueAsString(value));
            redisTemplate.opsForHash().put(key, "stage", stage);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", e);
        }
    }

    private String checkpointKey(Long mediaId) {
        return "agent:checkpoint:" + mediaId;
    }

    private String goalKey(Long mediaId, String goal) {
        return checkpointKey(mediaId) + ":goal:" + Integer.toHexString(String.valueOf(goal).hashCode());
    }

    private String feedbackKey(Long mediaId) {
        return "agent:feedback:" + mediaId;
    }
}

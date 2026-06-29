package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    public void saveContext(Long mediaId, VideoContext context) {
        write(checkpointKey(mediaId), "context", "CONTEXT_COMPLETED", context);
    }

    public void saveResult(Long mediaId, AgentState state) {
        write(goalKey(mediaId, state.goal()), "result", "ANALYSIS_COMPLETED", state);
    }

    public void savePlan(Long mediaId, String goal, AgentState.AgentPlan plan) {
        write(goalKey(mediaId, goal), "plan", "PLAN_COMPLETED", plan);
    }

    public void saveCriticState(Long mediaId, AgentState state) {
        write(goalKey(mediaId, state.goal()), "criticState", "CRITIC_COMPLETED", state);
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
}

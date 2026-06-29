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
        return read(mediaId, "context", VideoContext.class);
    }

    public AgentState loadResult(Long mediaId) {
        return read(mediaId, "result", AgentState.class);
    }

    public void saveContext(Long mediaId, VideoContext context) {
        write(mediaId, "context", "CONTEXT_COMPLETED", context);
    }

    public void saveResult(Long mediaId, AgentState state) {
        write(mediaId, "result", "ANALYSIS_COMPLETED", state);
    }

    private <T> T read(Long mediaId, String field, Class<T> type) {
        try {
            Object value = redisTemplate.opsForHash().get("agent:checkpoint:" + mediaId, field);
            return value == null ? null : objectMapper.readValue(value.toString(), type);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Agent Checkpoint 失败", e);
        }
    }

    private void write(Long mediaId, String field, String stage, Object value) {
        try {
            String key = "agent:checkpoint:" + mediaId;
            redisTemplate.opsForHash().put(key, field, objectMapper.writeValueAsString(value));
            redisTemplate.opsForHash().put(key, "stage", stage);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", e);
        }
    }
}

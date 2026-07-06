package com.example.server.dto;

import java.time.Instant;
import java.util.List;

public record AgentFeedback(
        Long mediaId,
        String goal,
        Integer rating,
        String errorType,
        String comment,
        String correctedGoal,
        List<String> correctedTasks,
        Long evidenceTimestamp,
        Boolean evidenceAccepted,
        Instant createdAt
) {
    public AgentFeedback normalized() {
        return new AgentFeedback(
                mediaId,
                goal,
                rating,
                errorType,
                comment,
                correctedGoal,
                correctedTasks == null ? List.of() : correctedTasks,
                evidenceTimestamp,
                evidenceAccepted,
                createdAt == null ? Instant.now() : createdAt
        );
    }
}

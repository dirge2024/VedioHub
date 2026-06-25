package com.example.server.dto;

import java.util.List;

/**
 * 受控 AgentLoop 的显式状态：目标、计划、当前产物、Critic 反馈和轮次。
 */
public record AgentState(
        String goal,
        AgentPlan plan,
        AnalysisResult result,
        CriticResult critique,
        int round
) {
    public record AgentPlan(
            String understoodGoal,
            List<String> tasks
    ) {
    }

    public record CriticResult(
            boolean passed,
            List<String> feedback,
            List<String> missingRequirements,
            List<String> unsupportedClaims,
            List<Long> requiredTimestamps
    ) {
    }
}

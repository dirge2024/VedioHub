package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentLoopService {

    private static final int MAX_ROUNDS = 2;

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    public AgentState run(VideoContext context) {
        AgentState.AgentPlan plan = deepSeekUtils.plan(context);
        AgentState state = new AgentState(context.userGoal(), plan, null, null, 0);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result = deepSeekUtils.execute(context, plan, state.critique());
            AgentState.CriticResult critique = deepSeekUtils.critique(context, plan, result);
            state = new AgentState(context.userGoal(), plan, result, critique, round);

            if (critique.passed()) {
                break;
            }
        }
        return state;
    }
}

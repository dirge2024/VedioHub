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

    @Autowired
    private LongVideoContextService longVideoContextService;

    public AgentState run(VideoContext context) {
        VideoContext relevantContext = longVideoContextService.selectRelevant(context);
        AgentState.AgentPlan plan = deepSeekUtils.plan(relevantContext);
        AgentState state = new AgentState(relevantContext.userGoal(), plan, null, null, 0);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result = deepSeekUtils.execute(relevantContext, plan, state.critique());
            AgentState.CriticResult critique = deepSeekUtils.critique(relevantContext, plan, result);
            state = new AgentState(relevantContext.userGoal(), plan, result, critique, round);

            if (critique.passed()) {
                break;
            }
        }
        return state;
    }
}

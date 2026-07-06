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

    @Autowired
    private AgentCheckpointService checkpointService;

    @Autowired
    private AgentTelemetry telemetry;

    public AgentState run(VideoContext context) {
        return run(null, context);
    }

    public AgentState run(Long mediaId, VideoContext context) {
        VideoContext relevantContext = longVideoContextService.selectRelevant(context);
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, relevantContext.userGoal());
        AgentState.AgentPlan plan = savedState == null ? null : savedState.plan();
        if (plan == null && mediaId != null) {
            plan = checkpointService.loadPlan(mediaId, relevantContext.userGoal());
        }
        if (plan == null) {
            plan = deepSeekUtils.plan(relevantContext);
            if (mediaId != null) checkpointService.savePlan(mediaId, relevantContext.userGoal(), plan);
        }
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = longVideoContextService.refineForCritique(
                    context, relevantContext, state.critique());
        }

        for (int round = state.round() + 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result = deepSeekUtils.execute(relevantContext, plan, state.critique());
            AgentState.CriticResult critique = deepSeekUtils.critique(relevantContext, plan, result);
            telemetry.incrementCurrent("criticRounds", 1);
            if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);
            state = new AgentState(relevantContext.userGoal(), plan, result, critique, round);

            if (mediaId != null) checkpointService.saveCriticState(mediaId, state);
            if (critique.passed()) {
                break;
            }
            relevantContext = longVideoContextService.refineForCritique(
                    context, relevantContext, critique);
        }
        if (mediaId != null) checkpointService.saveResult(mediaId, state);
        return state;
    }
}

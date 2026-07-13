package com.example.server.dto;

import java.io.Serializable;

public class AnalysisTaskMsg implements Serializable {

    public static final String START_ANALYSIS = "START_ANALYSIS";
    public static final String REVISE_ANALYSIS = "REVISE_ANALYSIS";

    private Long mediaId;
    private String action;
    private String contentHash;
    private String userGoal;

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action, String contentHash, String userGoal) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
        this.userGoal = userGoal;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }

    public boolean isRevision() {
        return REVISE_ANALYSIS.equals(action);
    }

    public boolean hasSupportedAction() {
        return START_ANALYSIS.equals(action) || REVISE_ANALYSIS.equals(action);
    }
}

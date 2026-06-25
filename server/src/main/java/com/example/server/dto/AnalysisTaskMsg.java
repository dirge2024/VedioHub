package com.example.server.dto;

import java.io.Serializable;

//必须实现Serializable接口，否则不能在网络上传输
public class AnalysisTaskMsg implements Serializable {
    private Long mediaId;
    private String action; //例如"START_ANALYSIS"
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
}

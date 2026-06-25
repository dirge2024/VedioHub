package com.example.server.dto;

import java.util.List;

/**
 * 长视频的五分钟语义块：摘要用于检索，原始片段用于命中后按需装载。
 */
public record VideoChunk(
        long startTime,
        long endTime,
        String segmentSummary,
        List<String> keywords,
        List<VideoContext.VideoSegment> rawSegments,
        List<Double> embedding
) {
    public record ChunkSummary(
            String segmentSummary,
            List<String> keywords
    ) {
    }
}

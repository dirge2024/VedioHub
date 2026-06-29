package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LongVideoContextService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;
    private static final int TOP_K = 3;

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    public VideoContext selectRelevant(VideoContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return context;
        }

        List<VideoChunk> chunks = buildChunks(context.segments());
        List<Double> queryEmbedding = embeddingUtils.embed(context.userGoal());

        List<VideoContext.VideoSegment> selectedSegments = chunks.stream()
                .sorted(Comparator.comparingDouble(
                        (VideoChunk chunk) -> hybridScore(
                                context.userGoal(), queryEmbedding, chunk)
                ).reversed())
                .limit(TOP_K)
                .flatMap(chunk -> chunk.rawSegments().stream())
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();

        return new VideoContext(context.source(), context.userGoal(), selectedSegments);
    }

    private List<VideoChunk> buildChunks(List<VideoContext.VideoSegment> segments) {
        List<VideoChunk> chunks = new ArrayList<>();
        for (long start = 0; start <= segments.get(segments.size() - 1).startMs(); start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long chunkStart = start;
            List<VideoContext.VideoSegment> rawSegments = segments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            VideoChunk.ChunkSummary summary = deepSeekUtils.summarizeChunk(rawSegments);
            String embeddingText = summary.segmentSummary() + "\n" + String.join(" ", summary.keywords());
            chunks.add(new VideoChunk(
                    start,
                    end,
                    summary.segmentSummary(),
                    summary.keywords(),
                    rawSegments,
                    embeddingUtils.embed(embeddingText)
            ));
        }
        return chunks;
    }

    private double hybridScore(String goal, List<Double> queryEmbedding, VideoChunk chunk) {
        // ponytail: 轻量关键词命中，数据量扩大后再升级分词器或 Reranker。
        long matched = chunk.keywords().stream()
                .filter(keyword -> goal != null && goal.contains(keyword))
                .count();
        double keywordScore = chunk.keywords().isEmpty() ? 0 : (double) matched / chunk.keywords().size();
        return cosine(queryEmbedding, chunk.embedding()) * 0.7 + keywordScore * 0.3;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }
}

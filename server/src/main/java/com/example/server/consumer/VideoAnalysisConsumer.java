package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.AgentState;
import com.example.server.service.AiService;
import com.example.server.service.AgentCheckpointService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-group}")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final AgentCheckpointService checkpointService;
    private final RocketMQTemplate rocketMQTemplate;
    private final String deadLetterTopic;

    public VideoAnalysisConsumer(AiService aiService,
                                 RedissonClient redissonClient,
                                 StringRedisTemplate redisTemplate,
                                 AgentCheckpointService checkpointService,
                                 RocketMQTemplate rocketMQTemplate,
                                 @Value("${rocketmq.topic.video-analysis-dead:video-analysis-dead-topic}")
                                 String deadLetterTopic) {
        this.aiService = aiService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.checkpointService = checkpointService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        if (msg == null || msg.getMediaId() == null || msg.getUserGoal() == null
                || msg.getUserGoal().isBlank() || !msg.hasSupportedAction()) {
            throw new IllegalArgumentException("invalid video analysis message");
        }
        Long mediaId = msg.getMediaId();
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(msg.getUserGoal());
        String lockKey = AnalysisTaskKeys.lock(contentHash, goalDigest);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        String completedKey = AnalysisTaskKeys.completed(contentHash, goalDigest);
        String attemptsKey = AnalysisTaskKeys.attempts(contentHash, goalDigest);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        boolean retrying = false;
        long attempt = 0;
        try {
            acquired = lock.tryLock();
            if (!acquired) {
                log.info("video_analysis_skipped mediaId={} acquired={}", mediaId, acquired);
                return;
            }
            Long currentAttempt = redisTemplate.opsForValue().increment(attemptsKey);
            attempt = currentAttempt == null ? 1 : currentAttempt;
            redisTemplate.expire(attemptsKey, ACTIVE_TTL);
            if (msg.isRevision()) {
                checkpointService.beginStagedRevision(mediaId, msg.getUserGoal());
                redisTemplate.delete(completedKey);
            } else {
                String completedMediaId = redisTemplate.opsForValue().get(completedKey);
                if (completedMediaId != null) {
                    Long sourceMediaId = parseMediaId(completedMediaId, completedKey);
                    AgentState reusable = sourceMediaId == null ? null
                            : checkpointService.loadResult(sourceMediaId, msg.getUserGoal());
                    if (reusable != null && reusable.result() != null
                            && aiService.reuseResult(mediaId, sourceMediaId, reusable)) {
                        log.info("video_analysis_reused mediaId={} sourceMediaId={}", mediaId, sourceMediaId);
                        return;
                    }
                    redisTemplate.delete(completedKey);
                }
            }
            aiService.asyncAnalyze(mediaId, msg.getUserGoal());
            redisTemplate.opsForValue().set(
                    completedKey, String.valueOf(mediaId), java.time.Duration.ofDays(7));
        } catch (Exception e) {
            if (acquired && attempt > 0 && attempt < MAX_DELIVERY_ATTEMPTS) {
                retrying = true;
                redisTemplate.expire(activeKey, ACTIVE_TTL);
                log.warn("video_analysis_retry_scheduled mediaId={} attempt={}", mediaId, attempt, e);
                throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", e);
            }
            if (acquired && attempt >= MAX_DELIVERY_ATTEMPTS) {
                try {
                    rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
                    log.error("video_analysis_dead_lettered mediaId={} attempts={}", mediaId, attempt, e);
                    return;
                } catch (RuntimeException deadLetterError) {
                    retrying = true;
                    deadLetterError.addSuppressed(e);
                    log.error("video_analysis_dead_letter_dispatch_failed mediaId={}", mediaId, deadLetterError);
                    throw deadLetterError;
                }
            }
            log.error("video_analysis_consume_failed mediaId={}", mediaId, e);
            throw new IllegalStateException("视频分析消费失败", e);
        } finally {
            if (acquired) {
                if (!retrying) redisTemplate.delete(java.util.List.of(activeKey, attemptsKey));
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private Long parseMediaId(String value, String completedKey) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(completedKey);
            log.warn("invalid_completed_media_reference key={} value={}", completedKey, value);
            return null;
        }
    }
}

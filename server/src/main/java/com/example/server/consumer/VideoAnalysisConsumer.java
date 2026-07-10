package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
//监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        String contentHash = msg.getContentHash();
        if (contentHash == null || !contentHash.matches("([a-f0-9]{32}|media-\\d+)")) {
            contentHash = "media-" + mediaId;
        }
        String goalDigest = UUID.nameUUIDFromBytes(
                String.valueOf(msg.getUserGoal()).trim().getBytes(StandardCharsets.UTF_8)).toString();
        String lockKey = "lock:analysis:" + contentHash + ":" + goalDigest;
        String activeKey = "analysis:active:" + contentHash + ":" + goalDigest;
        String completedKey = "analysis:completed:" + mediaId + ":" + goalDigest;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
            if (!acquired || Boolean.TRUE.equals(redisTemplate.hasKey(completedKey))) {
                return;
            }
            aiService.asyncAnalyze(mediaId, msg.getUserGoal());
            redisTemplate.opsForValue().set(completedKey, "1", 7, TimeUnit.DAYS);
        } catch (Exception e) {
            markAsFailed(mediaId, e.getMessage());
            throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", e);
        } finally {
            if (acquired) {
                redisTemplate.delete(activeKey);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private void markAsFailed(Long id, String error) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("❌ 分析失败: " + error);
            mediaFileMapper.updateById(file);
        }
    }
}

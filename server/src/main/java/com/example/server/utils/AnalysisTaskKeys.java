package com.example.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AnalysisTaskKeys {

    private static final Pattern MD5_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    private AnalysisTaskKeys() {
    }

    public static String normalizeContentHash(Long mediaId, String contentHash) {
        if (contentHash != null && MD5_PATTERN.matcher(contentHash).matches()) {
            return contentHash.toLowerCase(Locale.ROOT);
        }
        return "media-" + mediaId;
    }

    public static String goalDigest(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(goal.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String active(String contentHash, String goalDigest) {
        return "analysis:active:" + contentHash + ":" + goalDigest;
    }

    public static String lock(String contentHash, String goalDigest) {
        return "lock:analysis:" + contentHash + ":" + goalDigest;
    }

    public static String completed(String contentScope, String goalDigest) {
        return "analysis:completed:" + contentScope + ":" + goalDigest;
    }

    public static String attempts(String contentScope, String goalDigest) {
        return "analysis:attempts:" + contentScope + ":" + goalDigest;
    }

    /**
     * 内容级预处理的归属键：记录哪个 mediaId already 产出过该内容的 VideoContext。
     * ASR/OCR 只取决于视频内容本身，与用户目标无关，因此按 contentHash 而非 goal 复用。
     */
    public static String contextOwner(String contentHash) {
        return "analysis:context-owner:" + contentHash;
    }

    /**
     * 内容级预处理锁：同一视频被不同目标同时提交时，只允许一个消费者真正跑 ASR/OCR，
     * 其余等待后直接复用，避免重复烧算力与第三方额度。
     */
    public static String contextLock(String contentHash) {
        return "lock:analysis-context:" + contentHash;
    }
}

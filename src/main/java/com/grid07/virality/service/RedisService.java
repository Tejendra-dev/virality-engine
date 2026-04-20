package com.grid07.virality.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final StringRedisTemplate redisTemplate;

    // ─────────────────────────────────────────────
    // PHASE 2.1 — Virality Score
    // ─────────────────────────────────────────────

    public void incrementViralityScore(Long postId, String interactionType) {
        String key = "post:" + postId + ":virality_score";
        int points = switch (interactionType) {
            case "BOT_REPLY"       -> 1;
            case "HUMAN_LIKE"      -> 20;
            case "HUMAN_COMMENT"   -> 50;
            default -> 0;
        };
        redisTemplate.opsForValue().increment(key, points);
        log.info("Virality score for post {} incremented by {} ({})", postId, points, interactionType);
    }

    public Long getViralityScore(Long postId) {
        String val = redisTemplate.opsForValue().get("post:" + postId + ":virality_score");
        return val == null ? 0L : Long.parseLong(val);
    }

    // ─────────────────────────────────────────────
    // PHASE 2.2 — Horizontal Cap (max 100 bot replies per post)
    // ─────────────────────────────────────────────

    /**
     * Atomically increments bot_count and returns the NEW value.
     * If value > 100, the caller must reject the request.
     */
    public Long incrementAndGetBotCount(Long postId) {
        String key = "post:" + postId + ":bot_count";
        return redisTemplate.opsForValue().increment(key);
    }

    public void decrementBotCount(Long postId) {
        String key = "post:" + postId + ":bot_count";
        redisTemplate.opsForValue().decrement(key);
    }

    // ─────────────────────────────────────────────
    // PHASE 2.2 — Cooldown Cap (bot ↔ human, 10 min TTL)
    // ─────────────────────────────────────────────

    public boolean isBotOnCooldown(Long botId, Long humanId) {
        String key = "cooldown:bot_" + botId + ":human_" + humanId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void setBotCooldown(Long botId, Long humanId) {
        String key = "cooldown:bot_" + botId + ":human_" + humanId;
        redisTemplate.opsForValue().set(key, "1", Duration.ofMinutes(10));
    }

    // ─────────────────────────────────────────────
    // PHASE 3 — Notification Throttler
    // ─────────────────────────────────────────────

    public boolean hasRecentNotification(Long userId) {
        String key = "notif_cooldown:" + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void setNotificationCooldown(Long userId) {
        redisTemplate.opsForValue().set("notif_cooldown:" + userId, "1", Duration.ofMinutes(15));
    }

    public void pushPendingNotification(Long userId, String message) {
        redisTemplate.opsForList().rightPush("user:" + userId + ":pending_notifs", message);
    }

    public java.util.List<String> popAllPendingNotifications(Long userId) {
        String key = "user:" + userId + ":pending_notifs";
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) return java.util.Collections.emptyList();
        java.util.List<String> messages = redisTemplate.opsForList().range(key, 0, size - 1);
        redisTemplate.delete(key);
        return messages == null ? java.util.Collections.emptyList() : messages;
    }

    public java.util.Set<String> getAllPendingNotificationKeys() {
        return redisTemplate.keys("user:*:pending_notifs");
    }
}

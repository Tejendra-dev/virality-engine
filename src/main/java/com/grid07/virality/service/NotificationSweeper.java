package com.grid07.virality.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSweeper {

    private final RedisService redisService;

    /**
     * Runs every 5 minutes.
     * Scans all users with pending notifications,
     * pops them, and logs a summarized message.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes in milliseconds
    public void sweepPendingNotifications() {
        log.info("[ CRON SWEEPER ] Starting notification sweep...");

        Set<String> keys = redisService.getAllPendingNotificationKeys();
        if (keys == null || keys.isEmpty()) {
            log.info("[ CRON SWEEPER ] No pending notifications found.");
            return;
        }

        for (String key : keys) {
            // Extract userId from key pattern: user:{id}:pending_notifs
            String[] parts = key.split(":");
            if (parts.length < 2) continue;

            Long userId = Long.parseLong(parts[1]);
            List<String> notifications = redisService.popAllPendingNotifications(userId);

            if (notifications.isEmpty()) continue;

            // Build summarized message
            String firstNotif = notifications.get(0);
            int othersCount = notifications.size() - 1;

            if (othersCount > 0) {
                log.info("[ CRON SWEEPER ] Summarized Push Notification to User {}: {} and {} others interacted with your posts.",
                        userId, extractBotName(firstNotif), othersCount);
            } else {
                log.info("[ CRON SWEEPER ] Summarized Push Notification to User {}: {}",
                        userId, firstNotif);
            }
        }

        log.info("[ CRON SWEEPER ] Sweep complete.");
    }

    private String extractBotName(String message) {
        // message format: "Bot {id} replied to your post {postId}"
        String[] parts = message.split(" ");
        return parts.length >= 2 ? "Bot " + parts[1] : message;
    }
}

package com.grid07.virality.service;

import com.grid07.virality.dto.CreateCommentRequest;
import com.grid07.virality.dto.CreatePostRequest;
import com.grid07.virality.entity.Comment;
import com.grid07.virality.entity.Post;
import com.grid07.virality.repository.CommentRepository;
import com.grid07.virality.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final RedisService redisService;

    private static final int MAX_BOT_REPLIES = 100;
    private static final int MAX_DEPTH_LEVEL = 20;

    // ─────────────────────────────────────────────
    // PHASE 1 — Create Post
    // ─────────────────────────────────────────────

    @Transactional
    public Post createPost(CreatePostRequest req) {
        Post post = new Post();
        post.setAuthorId(req.getAuthorId());
        post.setAuthorType(Post.AuthorType.valueOf(req.getAuthorType().toUpperCase()));
        post.setContent(req.getContent());
        return postRepository.save(post);
    }

    // ─────────────────────────────────────────────
    // PHASE 1 + 2 — Add Comment with Guardrails
    // ─────────────────────────────────────────────

    @Transactional
    public Comment addComment(Long postId, CreateCommentRequest req) {
        // Validate post exists
        postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        boolean isBot = "BOT".equalsIgnoreCase(req.getAuthorType());

        if (isBot) {
            // GUARDRAIL 1: Vertical Cap — depth cannot exceed 20
            if (req.getDepthLevel() != null && req.getDepthLevel() > MAX_DEPTH_LEVEL) {
                throw new TooManyRequestsException(
                    "Vertical cap exceeded: depth level " + req.getDepthLevel() + " > " + MAX_DEPTH_LEVEL);
            }

            // GUARDRAIL 2: Cooldown Cap — bot cannot interact with same human within 10 min
            if (req.getHumanUserId() != null) {
                if (redisService.isBotOnCooldown(req.getAuthorId(), req.getHumanUserId())) {
                    throw new TooManyRequestsException(
                        "Cooldown active: Bot " + req.getAuthorId() +
                        " cannot interact with User " + req.getHumanUserId() + " yet.");
                }
            }

            // GUARDRAIL 3: Horizontal Cap — max 100 bot replies per post (ATOMIC)
            Long newCount = redisService.incrementAndGetBotCount(postId);
            if (newCount > MAX_BOT_REPLIES) {
                // Roll back the increment since we're rejecting
                redisService.decrementBotCount(postId);
                throw new TooManyRequestsException(
                    "Horizontal cap exceeded: post " + postId + " already has " + MAX_BOT_REPLIES + " bot replies.");
            }

            // Set cooldown AFTER passing all guardrails
            if (req.getHumanUserId() != null) {
                redisService.setBotCooldown(req.getAuthorId(), req.getHumanUserId());
            }

            // Update virality score
            redisService.incrementViralityScore(postId, "BOT_REPLY");

            // Handle notification for the human
            if (req.getHumanUserId() != null) {
                handleBotNotification(req.getHumanUserId(), req.getAuthorId(), postId);
            }
        } else {
            // Human comment — update virality score
            redisService.incrementViralityScore(postId, "HUMAN_COMMENT");
        }

        // All guardrails passed — save to DB
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setAuthorId(req.getAuthorId());
        comment.setAuthorType(Comment.AuthorType.valueOf(req.getAuthorType().toUpperCase()));
        comment.setContent(req.getContent());
        comment.setDepthLevel(req.getDepthLevel() != null ? req.getDepthLevel() : 0);
        return commentRepository.save(comment);
    }

    // ─────────────────────────────────────────────
    // PHASE 1 — Like a Post
    // ─────────────────────────────────────────────

    @Transactional
    public String likePost(Long postId, Long userId) {
        postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));
        redisService.incrementViralityScore(postId, "HUMAN_LIKE");
        return "Post " + postId + " liked! Virality score updated.";
    }

    // ─────────────────────────────────────────────
    // PHASE 3 — Notification Logic
    // ─────────────────────────────────────────────

    private void handleBotNotification(Long humanUserId, Long botId, Long postId) {
        String message = "Bot " + botId + " replied to your post " + postId;
        if (redisService.hasRecentNotification(humanUserId)) {
            // User already got a notification recently — queue it
            redisService.pushPendingNotification(humanUserId, message);
            log.info("Notification queued for user {}: {}", humanUserId, message);
        } else {
            // Send immediately and set cooldown
            log.info("Push Notification Sent to User {}: {}", humanUserId, message);
            redisService.setNotificationCooldown(humanUserId);
        }
    }
}

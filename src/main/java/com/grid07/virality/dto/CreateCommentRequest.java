package com.grid07.virality.dto;

import lombok.Data;

@Data
public class CreateCommentRequest {
    private Long authorId;
    private String authorType; // "USER" or "BOT"
    private String content;
    private Integer depthLevel;
    // For bot cooldown check: which human's post is this on?
    private Long humanUserId;
}

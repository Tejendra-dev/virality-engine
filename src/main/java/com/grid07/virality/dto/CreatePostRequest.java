package com.grid07.virality.dto;

import lombok.Data;

@Data
public class CreatePostRequest {
    private Long authorId;
    private String authorType; // "USER" or "BOT"
    private String content;
}

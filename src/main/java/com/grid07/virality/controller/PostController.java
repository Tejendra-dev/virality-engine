package com.grid07.virality.controller;

import com.grid07.virality.dto.CreateCommentRequest;
import com.grid07.virality.dto.CreatePostRequest;
import com.grid07.virality.entity.Comment;
import com.grid07.virality.entity.Post;
import com.grid07.virality.service.PostService;
import com.grid07.virality.service.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // POST /api/posts — Create a new post
    @PostMapping
    public ResponseEntity<?> createPost(@RequestBody CreatePostRequest request) {
        try {
            Post post = postService.createPost(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(post);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error creating post: " + e.getMessage());
        }
    }

    // POST /api/posts/{postId}/comments — Add a comment
    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Long postId,
            @RequestBody CreateCommentRequest request) {
        try {
            Comment comment = postService.addComment(postId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(comment);
        } catch (TooManyRequestsException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        }
    }

    // POST /api/posts/{postId}/like — Like a post
    @PostMapping("/{postId}/like")
    public ResponseEntity<?> likePost(
            @PathVariable Long postId,
            @RequestParam Long userId) {
        try {
            String result = postService.likePost(postId, userId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        }
    }
}

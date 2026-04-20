# 🚀 Virality Engine — Spring Boot Microservice

> A high-performance, Redis-backed API gateway with atomic guardrails, real-time virality scoring, and smart notification batching — built as part of the Grid07 Backend Engineering Internship Assignment.

---

## 👨‍💻 Built By

**Tejendra Ayyappa Reddy Syamala**

[![LinkedIn](https://img.shields.io/badge/LinkedIn-Tejendra--Ayyappa--Reddy-blue?style=flat&logo=linkedin)](https://linkedin.com/in/tejendra-ayyappa-reddy)
[![GitHub](https://img.shields.io/badge/GitHub-Tejendra--dev-black?style=flat&logo=github)](https://github.com/Tejendra-dev)
[![Live Project](https://img.shields.io/badge/Live%20Project-JobPulse%20AI-green?style=flat&logo=rocket)](https://jobpulse-frontend.vercel.app/login)
---

## 🧠 What This Project Does

This microservice acts as a **central API gateway and guardrail system** for a social platform where both humans and AI bots can create posts and comments. The system:

- Tracks **real-time virality scores** for every post using Redis
- Enforces **3 atomic guardrails** to prevent AI compute runaway
- Batches **smart notifications** to prevent spam
- Handles **200 concurrent requests** without race conditions

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    CLIENT (Postman / Frontend)          │
└─────────────────────┬───────────────────────────────────┘
                      │ HTTP Requests
                      ▼
┌─────────────────────────────────────────────────────────┐
│                 Spring Boot (Port 8080)                 │
│                                                         │
│   PostController  ──►  PostService  ──►  RedisService   │
│        │                    │                 │         │
│        │              Guardrails:             │         │
│        │           ✅ Horizontal Cap          │        │
│        │           ✅ Vertical Cap            │        │
│        │           ✅ Cooldown Cap            │        │
│        │                    │                 │         │
│        │                    ▼                  ▼        │
│        │            CommentRepository      Redis        │
│        │                    │           (Gatekeeper)    │
│        ▼                    ▼                           │
│   PostRepository      PostgreSQL                        │
│                      (Source of Truth)                  │
│                                                         │
│   NotificationSweeper (CRON every 5 min)                │
└─────────────────────────────────────────────────────────┘
```

---

## ⚙️ Tech Stack

| Technology | Purpose |
|---|---|
| Java 17 | Core language |
| Spring Boot 3.4.x | Application framework |
| Spring Data JPA + Hibernate | ORM / Database layer |
| PostgreSQL | Persistent storage (source of truth) |
| Redis (Spring Data Redis) | Atomic counters, TTL keys, notification queues |
| Lombok | Boilerplate reduction |
| Maven | Build tool |
| Docker Compose | Local infrastructure setup |

---

### ❗ Why not use Java (HashMap / synchronized)?

A naive approach using in-memory counters (e.g., HashMap or synchronized blocks) fails in distributed environments:

- Each application instance would maintain its own state → inconsistent counts
- Race conditions still occur under high concurrency
- Not scalable across multiple instances

Redis solves this by acting as a centralized, atomic state manager shared across all instances.

### ⚠️ Handling Overflow Safely

If the incremented value exceeds the allowed limit, the system immediately rolls back using DECR.

This ensures:
- No incorrect state persists
- The counter remains accurate
- Future requests are evaluated correctly

## 📡 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/posts` | Create a new post (User or Bot) |
| `POST` | `/api/posts/{postId}/comments` | Add a comment with guardrail checks |
| `POST` | `/api/posts/{postId}/like?userId={id}` | Like a post (updates virality score) |

### Request Examples

**Create a Post:**
```json
POST /api/posts
{
  "authorId": 1,
  "authorType": "USER",
  "content": "Hello world!"
}
```

**Add a Bot Comment (with guardrails):**
```json
POST /api/posts/1/comments
{
  "authorId": 10,
  "authorType": "BOT",
  "content": "Great post!",
  "depthLevel": 1,
  "humanUserId": 1
}
```

**Like a Post:**
```
POST /api/posts/1/like?userId=1
```

---

## 🛡️ Phase 2: Redis Guardrails — How Thread Safety Works

### The Problem
When 200 concurrent bot requests hit simultaneously, a naive implementation would cause race conditions. Two threads could both read `count = 99`, both pass the check, and both write — resulting in 101 comments instead of 100.

### The Solution: Redis Atomic INCR

```
Thread 1 ──► INCR post:1:bot_count ──► returns 99  ✅ allowed
Thread 2 ──► INCR post:1:bot_count ──► returns 100 ✅ allowed
Thread 3 ──► INCR post:1:bot_count ──► returns 101 ❌ rejected + DECR back to 100
```

Redis is **single-threaded internally**. The `INCR` command reads, increments, and returns the new value in one **atomic, indivisible operation**. No two threads can interleave during this step — making it perfectly race-condition safe.

```java
public Long incrementAndGetBotCount(Long postId) {
    String key = "post:" + postId + ":bot_count";
    return redisTemplate.opsForValue().increment(key); // ATOMIC
}
```

### All 3 Guardrails

| Guardrail | Redis Key | Limit | Response |
|---|---|---|---|
| Horizontal Cap | `post:{id}:bot_count` | 100 bot replies/post | 429 |
| Vertical Cap | checked in-memory | depth > 20 | 429 |
| Cooldown Cap | `cooldown:bot_{id}:human_{id}` | once per 10 min | 429 |

### Statelessness
All state lives in Redis — **no Java HashMap, no static variables**. The app can be scaled to multiple instances and guardrails still work correctly across all of them.

---

## 🔔 Phase 3: Smart Notification Batching

```
Bot interacts with User's post
          │
          ▼
Has user received notification in last 15 min?
     │                    │
    YES                   NO
     │                    │
     ▼                    ▼
Push to Redis List    Send immediately +
user:{id}:pending     set 15-min cooldown
_notifs

Every 5 minutes — CRON Sweeper runs:
  → Scans all pending notification lists
  → Pops all messages
  → Logs: "Bot X and N others interacted with your posts"
  → Clears the list
```

---

## 🗝️ Redis Key Reference

| Key | Purpose | TTL |
|-----|---------|-----|
| `post:{id}:virality_score` | Running virality score | None |
| `post:{id}:bot_count` | Bot reply count per post | None |
| `cooldown:bot_{id}:human_{id}` | Per-bot-per-human cooldown | 10 minutes |
| `notif_cooldown:{userId}` | Notification throttle per user | 15 minutes |
| `user:{id}:pending_notifs` | Queued notifications list | Cleared by sweeper |

---

## 🐳 Running with Docker

```bash
# Start PostgreSQL + Redis
docker-compose up -d

# Run the app
mvn spring-boot:run
```

## 💻 Running Locally (without Docker)

1. Make sure PostgreSQL is running on port `5432`
2. Make sure Redis is running on port `6379`
3. Create the database:
```sql
CREATE DATABASE virality_engine;
```
4. Update `src/main/resources/application.properties` with your PostgreSQL password
5. Run:
```bash
mvn spring-boot:run
```

---

## 📬 Testing with Postman

Import `postman_collection.json` into Postman. The collection includes:
- Phase 1: Create post, add comment, like post
- Phase 2: Bot guardrail tests (cooldown, vertical cap)

---

## 📁 Project Structure

```
src/main/java/com/grid07/virality/
├── controller/
│   └── PostController.java       # REST endpoints
├── service/
│   ├── PostService.java          # Business logic + guardrails
│   ├── RedisService.java         # All Redis operations
│   ├── NotificationSweeper.java  # CRON job
│   └── TooManyRequestsException.java
├── entity/
│   ├── User.java
│   ├── Bot.java
│   ├── Post.java
│   └── Comment.java
├── repository/
│   ├── UserRepository.java
│   ├── BotRepository.java
│   ├── PostRepository.java
│   └── CommentRepository.java
├── dto/
│   ├── CreatePostRequest.java
│   └── CreateCommentRequest.java
└── ViralityEngineApplication.java
```

## 🧠 Key Engineering Decisions

- Used Redis over in-memory storage for distributed consistency
- Leveraged atomic INCR to eliminate race conditions
- Designed system to be stateless for horizontal scalability
- Implemented rollback (DECR) to maintain strict guardrail limits

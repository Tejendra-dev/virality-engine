# Virality Engine — Spring Boot Microservice

A high-performance Spring Boot microservice implementing a Redis-backed virality scoring system with atomic guardrails and smart notification batching.

---

## Tech Stack

- Java 17
- Spring Boot 3.4.x
- PostgreSQL (source of truth for content)
- Redis (gatekeeper for all guardrails and counters)
- Lombok

---

## How to Run

### Option 1: Docker (recommended)
```bash
docker-compose up -d
mvn spring-boot:run
```

### Option 2: Local PostgreSQL + Redis
1. Make sure PostgreSQL is running on port 5432
2. Make sure Redis is running on port 6379
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

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/posts` | Create a new post |
| POST | `/api/posts/{postId}/comments` | Add a comment (with guardrails for bots) |
| POST | `/api/posts/{postId}/like?userId={id}` | Like a post |

### Example Requests

**Create a Post:**
```json
POST /api/posts
{
  "authorId": 1,
  "authorType": "USER",
  "content": "Hello world!"
}
```

**Add a Bot Comment:**
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

## How Thread Safety is Guaranteed for Atomic Locks (Phase 2)

### The Problem
When 200 concurrent bot requests hit the API simultaneously, a naive implementation using Java variables or database reads would cause race conditions — two requests could both read `count = 99`, both think they're under the limit, and both write to the DB, resulting in 101 comments instead of 100.

### The Solution: Redis INCR

The horizontal cap uses Redis's `INCR` command via Spring Data Redis:

```java
public Long incrementAndGetBotCount(Long postId) {
    String key = "post:" + postId + ":bot_count";
    return redisTemplate.opsForValue().increment(key);
}
```

**Why this is atomic:** Redis is single-threaded internally. The `INCR` command is an atomic operation — it reads, increments, and returns the new value in a single indivisible step. No two requests can interleave during this operation. This means:

- Request 1 calls INCR → gets 99 ✅ (allowed)
- Request 2 calls INCR → gets 100 ✅ (allowed)  
- Request 3 calls INCR → gets 101 ❌ (rejected + decremented back to 100)

The counter is rolled back with `DECR` if the request is rejected, keeping the count accurate.

### Statelessness
All state lives in Redis — no Java `HashMap`, no `static` variables, no in-memory counters. This means the Spring Boot app can be scaled horizontally (multiple instances) and the guardrails still work correctly across all instances.

### Database Safety
`@Transactional` ensures that the PostgreSQL write only happens after all Redis guardrails pass. If the DB write fails, the transaction rolls back — but the Redis counter has already been incremented. This is an acceptable trade-off since Redis is the gatekeeper, not the source of truth.

---

## Redis Key Reference

| Key | Purpose | TTL |
|-----|---------|-----|
| `post:{id}:virality_score` | Running virality score | None |
| `post:{id}:bot_count` | Bot reply count per post | None |
| `cooldown:bot_{id}:human_{id}` | Per-bot-per-human cooldown | 10 minutes |
| `notif_cooldown:{userId}` | Notification throttle per user | 15 minutes |
| `user:{id}:pending_notifs` | Queued notifications list | None (cleared by sweeper) |

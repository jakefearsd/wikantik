---
title: Redis Patterns
type: article
cluster: data-systems
status: active
date: '2026-04-25'
tags:
- redis
- caching
- queue
- rate-limiting
- distributed-locks
summary: The patterns Redis is good at — caching, rate limiting, distributed
  locks, queues, leaderboards, pub/sub — and the ones it just looks like it's
  good at until you try them.
related:
- CachingStrategies
- ApiRateLimitingAlgorithms
- ConsistentHashing
- DistributedComputingAlgorithms
hubs:
- DataSystems Hub
---
# Redis Patterns

Redis is the in-memory data structure server that became "the swiss army knife" of backend engineering — caches, queues, rate limits, pub/sub, leaderboards, geo. The data structures and the speed (sub-millisecond, 100k+ ops/sec on a single node) make it tempting to use for everything.

It is not a database. It is not durable in the way Postgres is durable. It is not a substitute for a real message queue. Knowing where the lines are is the difference between Redis being a delight and being a footgun.

## Cache-aside

The 95% use case for Redis. Application checks cache; on miss, fetches from the source-of-truth (DB), populates cache.

```python
def get_user(id):
    cached = redis.get(f"user:{id}")
    if cached: return json.loads(cached)
    user = db.query(...)
    redis.setex(f"user:{id}", 3600, json.dumps(user))  # 1 hour TTL
    return user
```

Considerations:

- **TTL is your friend.** Without a TTL, stale data lives forever in cache.
- **Invalidate on writes.** When you update the row, also `DELETE` the cache key.
- **Stampede protection.** When a popular cache key expires, many requests miss simultaneously and hit the DB. Mitigate with stale-while-revalidate (return stale; refresh asynchronously) or a single-flight pattern (one request fetches; others wait).
- **Cache stampede on cold start.** A new cache (or after a Redis restart) has 0% hit rate; the DB sees full traffic. Pre-warm critical keys; rate-limit; have headroom on the DB.

See [CachingStrategies] for cache-aside vs write-through vs other patterns.

## Rate limiting

Token bucket is the typical pattern. Use a Lua script for atomicity:

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1]) or limit
local last_refill = tonumber(data[2]) or now

local elapsed = now - last_refill
tokens = math.min(limit, tokens + elapsed * refill_rate)

if tokens < 1 then
    redis.call('HSET', key, 'tokens', tokens, 'last_refill', now)
    return 0  -- rejected
end

tokens = tokens - 1
redis.call('HSET', key, 'tokens', tokens, 'last_refill', now)
redis.call('EXPIRE', key, 3600)
return 1  -- allowed
```

Atomic, fast, correct. See [ApiRateLimitingAlgorithms] for the full pattern.

## Distributed locks (with caveats)

The naive pattern:

```python
def acquire_lock(key, timeout):
    return redis.set(key, value, nx=True, ex=timeout)
```

`SET NX EX` is atomic. If it returns OK, you have the lock; release with a script that checks the value to prevent another caller from accidentally releasing your lock.

The catch: this is **not** safe under all failure conditions. Martin Kleppmann's analysis (2017) and counter-arguments are worth reading. The short version:

- If the Redis primary fails and the replica becomes primary before the SET propagates, two clients can hold the "lock" simultaneously.
- Network partitions cause false-but-still-running lock holders to lose mutex without knowing.

For locks where correctness matters (financial transactions, irreversible operations), use a real consensus system (etcd, ZooKeeper, Consul). For locks where best-effort suffices (rate-limiting periodic jobs, cron singleton enforcement), Redis-based locks are fine.

`Redlock` (Redis's recommended distributed lock) tries to address the failover concern by acquiring on a majority of independent Redis nodes. It improves things but doesn't fully solve the GC-pause / fencing-token problem.

Rule of thumb: Redis lock for "we'd prefer not to run two of these at once but it's not catastrophic if we do." etcd / ZooKeeper for "two of these running at once would be catastrophic."

## Queues (with caveats)

Redis can be a queue. Two patterns:

### List-based (LPUSH / BRPOP)

```
LPUSH queue "job1"
BRPOP queue 30  # blocking pop, 30s timeout
```

Simple, fast. Good for fire-and-forget jobs where loss is tolerable.

Limitations:
- No ack / retry. Once popped, the job is gone — even if the worker crashes mid-processing.
- No persistence guarantees if Redis crashes mid-write.
- No consumer groups (everyone reads from the same queue).

### Streams

`XADD` / `XREAD` / `XACK` — Redis 5+. Has consumer groups, message acks, pending entries lists, retention. Closer to Kafka than to a list.

```
XADD orders * order_id 42 status pending
XREADGROUP GROUP processors worker1 COUNT 10 STREAMS orders >
XACK orders processors <message-id>
```

Production-ready for moderate workloads. Above ~10k messages/sec sustained, Kafka starts winning on durability and operational maturity.

When to use Redis for queueing:

- **Job queues with cheap re-execution.** Background work that's idempotent and fine to lose occasionally.
- **Streams for moderate-volume durable messaging** when adding Kafka would be overkill.
- **Real-time pub/sub fan-out.**

When not:

- **Mission-critical durable messaging.** Use Kafka, RabbitMQ, SQS.
- **High volume with strict ordering.** Kafka.
- **Complex workflow orchestration.** Temporal, Airflow.

## Pub/sub

`PUBLISH` / `SUBSCRIBE`:

```
SUBSCRIBE notifications:user:42
PUBLISH notifications:user:42 "{"type":"new_message","id":12345}"
```

Fire-and-forget. Subscribers receive messages only while connected. Disconnected subscriber misses everything that was published while disconnected.

Use for:
- Real-time UI updates within an active session.
- Cross-instance cache invalidation.
- Loose-coupling notifications inside one application.

Don't use for:
- Persistent messaging (use Streams).
- Inter-service communication where messages must not be lost.

## Counters and analytics

Atomic counters at speed:

```
INCR pageview:home:2026-04-25
INCRBYFLOAT revenue:total 19.99
HINCRBY user:42:counters orders 1
```

Hyperloglog for cardinality estimates (unique-visitor counts):

```
PFADD daily_visitors "user_42" "user_43" ...
PFCOUNT daily_visitors  -- approximate; uses 12KB regardless of count
```

Sorted sets for leaderboards:

```
ZADD leaderboard 9870 "alice" 8420 "bob"
ZREVRANGE leaderboard 0 9 WITHSCORES  -- top 10
ZREVRANK leaderboard "alice"          -- their rank
```

This is where Redis is almost magically efficient. Real-time analytics with millions of events / second, no specialized analytics database.

## Sessions

Cache user session data with a TTL keyed by session ID. Simple, fast, scales. Most web frameworks have built-in Redis session store backends.

Caveat: if Redis is your only session store and Redis goes down, all users are logged out. Persist sessions to a durable store too if this matters.

## Geo

`GEOADD`, `GEORADIUS`, `GEOSEARCH`. Stores lat/lng for keys; queries within radius or rectangle.

Useful for "find users near me," store locator, ride-sharing matches. Doesn't replace PostGIS for serious GIS but covers a lot of ground.

## Cluster vs single node

Single Redis node: ~100k ops/sec sustained. Adequate for most applications.

Redis Cluster: shards data across nodes. Adds:

- More memory (sum across nodes).
- More throughput (sum across nodes).
- Operational complexity.
- Limitation: multi-key operations require keys to be in the same hash slot (use hash tags `{user42}:orders` to colocate).

Most teams under 50k ops/sec with < 100GB working set don't need cluster. Promote when you do.

## Persistence

Redis offers two modes:

- **RDB snapshots** — periodic full snapshots. Fast restart; can lose minutes of data.
- **AOF (Append-Only File)** — every write logged. Slower; near-zero data loss with `fsync=always` (rarely used; usually `everysec`).

For cache-only Redis: persistence off (the cache is recoverable). For Redis-as-database: AOF with `everysec` and replicas.

If you're using Redis as the source of truth for important data, reconsider. Redis is designed as a cache; durability is a backstop, not the focus.

## Redis-compatible alternatives

By 2026, the Redis ecosystem has split. The original Redis Inc. moved to a non-OSS license; the community forked into:

- **Valkey** (Linux Foundation fork) — drop-in replacement; community-driven; main fork most large users moved to.
- **KeyDB** — multithreaded fork; faster on large machines.
- **Dragonfly** — alternative implementation; multithreaded, claims much higher throughput.
- **Garnet** (Microsoft) — alternative implementation; fast.

For a new deployment in 2026, Valkey is the most common pick. Functionally equivalent to Redis for the patterns above.

## Common mistakes

- **No TTLs.** Keys live forever; memory grows. Set TTLs on most keys.
- **Big keys (>1MB).** Slow operations, memory fragmentation. Split or store outside Redis.
- **`KEYS` in production.** O(N) blocks the entire server. Use `SCAN`.
- **Sync calls during page load.** Even sub-millisecond Redis adds up across many calls. Pipeline or batch.
- **Treating Redis as durable when persistence isn't tuned.** Default config can lose minutes of data on crash.
- **Connection pool too small.** Threads block waiting for a Redis connection while Redis is idle. Tune the pool.

## Further reading

- [CachingStrategies] — broader caching patterns
- [ApiRateLimitingAlgorithms] — rate-limiting algorithms in detail
- [ConsistentHashing] — distributed sharding mechanics
- [DistributedComputingAlgorithms] — distributed primitives

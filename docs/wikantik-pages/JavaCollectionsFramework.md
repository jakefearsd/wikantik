---
canonical_id: 01KQ0P44R9KG70GP7TP45YESDY
title: Java Collections Framework
type: article
cluster: java
status: active
date: '2026-04-26'
summary: The Java collections in practical use — which List, Set, Map to use when,
  the immutable variants, the modern sequenced-collection additions, and the trade-offs
  that determine the right choice.
tags:
- java
- collections
- list
- map
- set
related:
- JavaStreamsAndFunctionalProgramming
- JavaTwentyOneFeatures
- ImmutableDataPatterns
- JavaRecordsAndSealedClasses
hubs:
- Java Hub
---
# Java Collections Framework

The Java collections framework has 25+ years of history. The core types are stable; the modern additions (immutable factories, sequenced collections) round out long-standing gaps. This page is the practical guide — which collection to pick when, the immutable variants, and the patterns that have aged well.

## The core types

### List

Ordered, allows duplicates, indexed access.

| Implementation | When to use |
|----------------|-------------|
| `ArrayList` | Default. Backed by array; fast random access; slow inserts in middle |
| `LinkedList` | Almost never. Doubly-linked list; rarely the right answer |
| `CopyOnWriteArrayList` | Read-heavy concurrent access; writes copy the entire array |

Default to `ArrayList`. The cases for the others are narrow.

### Set

Unordered (or ordered), no duplicates.

| Implementation | When to use |
|----------------|-------------|
| `HashSet` | Default. Hash-based, no order |
| `LinkedHashSet` | Insertion-order iteration matters |
| `TreeSet` | Natural-order or custom-order iteration |
| `ConcurrentSkipListSet` | Concurrent ordered set |
| `EnumSet` | Set of enum values; bit-vector backed; very fast |

`HashSet` is the right default for most cases. `LinkedHashSet` if you need to preserve insertion order.

### Map

Key-value pairs, unique keys.

| Implementation | When to use |
|----------------|-------------|
| `HashMap` | Default. Fast; no ordering |
| `LinkedHashMap` | Insertion-order or access-order matters |
| `TreeMap` | Sorted by key |
| `ConcurrentHashMap` | Concurrent map (the right concurrent map) |
| `EnumMap` | Map keyed by enum; very fast |

`HashMap` for general use. `ConcurrentHashMap` for shared mutable maps across threads. `EnumMap` when keyed by enum (faster, less memory).

### Queue / Deque

| Implementation | When to use |
|----------------|-------------|
| `ArrayDeque` | Default for stack/queue use; faster than LinkedList |
| `PriorityQueue` | Heap-based priority queue |
| `LinkedBlockingQueue` | Producer-consumer with blocking semantics |
| `ConcurrentLinkedQueue` | Concurrent queue, non-blocking |

`ArrayDeque` for non-concurrent stack/queue. The `Stack` class is legacy; do not use it.

## Immutable factories (Java 9+)

```java
List<String> names = List.of("Alice", "Bob", "Carol");
Set<Integer> ports = Set.of(80, 443, 8080);
Map<String, String> headers = Map.of("Content-Type", "application/json");
```

The result is genuinely immutable — modification throws `UnsupportedOperationException`. These are the right default for "I have a small fixed collection."

For larger or computed immutable collections:

```java
List<X> result = stream.collect(Collectors.toUnmodifiableList());
// or in Java 16+
List<X> result = stream.toList();
```

## Sequenced collections (Java 21+)

Long-standing API gaps filled. `SequencedCollection`, `SequencedSet`, `SequencedMap` interfaces add:

- `getFirst()`, `getLast()`
- `addFirst(e)`, `addLast(e)`
- `reversed()` — returns a reversed view
- `removeFirst()`, `removeLast()`

Implemented by `List`, `LinkedHashSet`, `LinkedHashMap`, `Deque`, etc. Removes the need for awkward `iterator().next()` patterns to access the first element.

## Iteration patterns

### Enhanced for loop (default)

```java
for (String name : names) {
    process(name);
}
```

Almost always the right way to iterate when you don't need the index.

### Iterator (when you need to remove during iteration)

```java
Iterator<String> it = names.iterator();
while (it.hasNext()) {
    if (shouldRemove(it.next())) {
        it.remove();
    }
}
```

### Index-based (when you need the index)

```java
for (int i = 0; i < names.size(); i++) {
    System.out.println(i + ": " + names.get(i));
}
```

### Streams (for transformation/aggregation)

```java
names.stream()
    .filter(n -> n.length() > 5)
    .forEach(this::process);
```

See [JavaStreamsAndFunctionalProgramming](JavaStreamsAndFunctionalProgramming).

## Common operations and their costs

| Operation | ArrayList | LinkedList | HashMap | TreeMap |
|-----------|-----------|------------|---------|---------|
| Get by index | O(1) | O(n) | n/a | n/a |
| Get by key | n/a | n/a | O(1) avg | O(log n) |
| Insert at end | O(1) amortized | O(1) | O(1) avg | O(log n) |
| Insert at front | O(n) | O(1) | n/a | n/a |
| Remove by value | O(n) | O(n) | O(1) avg | O(log n) |
| Iteration | O(n) | O(n) | O(n) | O(n) ordered |

The "amortized" on ArrayList insert: occasionally an internal array resize is O(n), averaged out across many inserts.

## Memory and capacity

- `ArrayList(int initialCapacity)` — provide expected size if known to avoid resizing
- `HashMap(int initialCapacity)` — initial bucket count; avoid rehashing
- `HashMap(int capacity, float loadFactor)` — load factor 0.75 is the default

Pre-sizing collections has a real performance impact when sizes are known and large.

## Thread safety

Default collections are not thread-safe. Three options for concurrent access:

1. **Concurrent collections** (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `ConcurrentLinkedQueue`) — designed for concurrent access
2. **Synchronized wrappers** (`Collections.synchronizedXxx`) — single-lock approach; simple but contention can be high
3. **Immutable collections** — no synchronization needed; readers see a consistent snapshot

For maps shared across threads, `ConcurrentHashMap` is almost always the right choice.

## Common failure patterns

- **Using `LinkedList` because "it's faster for inserts."** It isn't, in practice. Use `ArrayList`.
- **Synchronizing every collection access.** Often unnecessary if the collection is single-threaded; expensive if concurrent collections would work better.
- **Mutating collections during iteration.** ConcurrentModificationException. Use `Iterator.remove()` or copy.
- **`HashMap` resize thrashing.** Pre-size if you know the count.
- **Returning mutable collections from APIs.** Caller can break invariants. Return immutable copies.
- **Using `Vector` or `Hashtable`.** Legacy, synchronized for everything. Use modern equivalents.

## Further Reading

- [JavaStreamsAndFunctionalProgramming](JavaStreamsAndFunctionalProgramming) — Streams operate on collections
- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Sequenced collections and other additions
- [ImmutableDataPatterns](ImmutableDataPatterns) — Immutable collection design
- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Records often flow through collections
- [Java Hub](Java+Hub) — Cluster index

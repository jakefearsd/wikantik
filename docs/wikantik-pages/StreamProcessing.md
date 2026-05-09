---
cluster: distributed-systems
canonical_id: 01KQ0P44X0FP4KR5P9YBCQG5JY
title: Stream Processing
type: article
tags:
- streaming
- flink
- watermarks
- windowing
summary: A technical guide to stream processing mechanics, focusing on temporal correctness via Watermarking and the distinct behaviors of Tumbling and Sliding windows.
auto-generated: false
date: 2024-05-16
---
# Stream Processing: Managing Temporal Complexity

Stream processing is the continuous computation of unbounded datasets. Unlike batch processing, which operates on a static snapshot, stream processing must handle data that arrives late, out-of-order, or over inconsistent network conditions.

## 1. The Challenge of Time

To achieve correctness, we must distinguish between:
*   **Event Time:** When the event actually occurred (the source timestamp).
*   **Processing Time:** When the engine actually sees the event.

In distributed systems, the gap between these two is non-deterministic. We rely on **Event Time** for accurate business logic (e.g., "how many clicks happened between 2:00 and 2:05?").

## 2. Watermarking: Taming Lateness

A **Watermark** is a control signal that tells the system: "I am confident that no more events with a timestamp earlier than $T$ will arrive."

### 2.1 How Watermarks Work
As events flow through the system, the engine tracks the maximum observed event time ($T_{max}$). It emits a watermark at $T_{watermark} = T_{max} - \text{slack}$.
*   When a watermark for 2:00 PM passes an operator, it triggers the computation for all time-windows ending at or before 2:00 PM.
*   Events arriving *after* their corresponding watermark are considered "late data" and are either dropped or handled by a specific "side output" for correction.

## 3. Windowing Mechanics

Windowing allows us to group unbounded streams into finite chunks for aggregation.

### 3.1 Tumbling Windows (Fixed, Non-overlapping)
Tumbling windows partition the stream into discrete, equal-sized segments.
*   **Example:** A 1-minute tumbling window.
*   **Behavior:** An event at 12:00:59 falls into the `[12:00, 12:01]` window. An event at 12:01:01 falls into the `[12:01, 12:02]` window.
*   **Use Case:** Simple periodic reporting (e.g., total sales per hour).

### 3.2 Sliding Windows (Overlapping)
Sliding windows have a fixed length but "slide" by a specific interval (the slide).
*   **Example:** A 5-minute window that slides every 1 minute.
*   **Behavior:** At any given moment, multiple windows are "open." An event at 12:03 will contribute to five different windows (e.g., the window starting at 12:00, 12:01, 12:02, and 12:03).
*   **Use Case:** Rolling averages or "moving" trends (e.g., "average latency over the last 5 minutes, updated every minute").

### 3.3 Session Windows (Activity-based)
Session windows do not have a fixed size. They are defined by a "gap" of inactivity.
*   **Behavior:** A new window starts when an event arrives and stays open as long as events continue to arrive within the gap duration. If no events arrive for $X$ minutes, the window closes.
*   **Use Case:** Analyzing user sessions on a website.

## 4. State and Fault Tolerance

Stream processors (like Apache Flink) must maintain **State** (e.g., the current sum in a window). To ensure "Exactly-Once" semantics, the system periodically takes **Checkpoints**—consistent snapshots of the distributed state. In the event of a failure, the system rolls back to the last checkpoint and replays the stream from the corresponding offset in the message broker (e.g., Kafka).

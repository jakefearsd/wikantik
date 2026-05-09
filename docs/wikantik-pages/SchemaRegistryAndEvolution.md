---
canonical_id: 01KQEKGDFEFG0BHM7YDKRAXRAM
title: Schema Registry and Evolution
type: article
cluster: data-engineering
status: active
date: '2026-05-24'
tags:
- schema
- avro
- protobuf
- json-schema
- compatibility
- kafka
summary: Engineering patterns for evolving data schemas in distributed systems without breaking downstream consumers. Covers Avro vs. Protobuf and compatibility modes.
auto-generated: false
---
# Schema Registry and Evolution

In a decoupled architecture (e.g., Kafka, gRPC), the "contract" between services is the schema. A Schema Registry acts as the single source of truth and the gatekeeper for evolution, ensuring that a producer cannot ship a change that crashes its consumers.

## The Compatibility Matrix

When updating a schema, you must select a compatibility mode. This decision dictates whether you update consumers or producers first.

| Mode | Who can read what? | Update Order |
|---|---|---|
| **Backward** | New consumer can read old data. | Consumers first. |
| **Forward** | Old consumer can read new data. | Producers first. |
| **Full** | Both are true. | Any order. |
| **None** | No checks. | Dangerous; requires coordinated downtime. |

## Practical Evolution Rules (Avro / Protobuf)

- **Adding Fields:** Safe if they are optional or have a default value.
- **Removing Fields:** Safe only in Backward compatibility if the consumers are updated to stop looking for the field before it disappears.
- **Renaming Fields:** Always a breaking change in Avro (use aliases). Safe in Protobuf if the **Field ID** remains the same.
- **Changing Types:** Generally breaking. (e.g., `int` to `string` is not compatible).

### Protobuf Field ID Discipline
Protobuf relies on integer tags, not field names. Renaming `user_name` to `username` is fine; changing tag `1` to tag `2` is a catastrophic failure.

```proto
message User {
  // Field 1 was removed in v2.0. DO NOT REUSE THE ID.
  reserved 1; 
  reserved "old_field_name";

  string username = 2; // Use ID 2
  int32 age = 3;
}
```

## The Schema Registry Workflow

1. **Producer** attempts to register `Schema v2`.
2. **Registry** checks `v2` against `v1` using the configured compatibility rule (e.g., BACKWARD).
3. **Registry** rejects the schema if it contains a breaking change (e.g., adding a required field without a default).
4. **Consumer** fetches `v2` from the registry by ID when it encounters a message it doesn't recognize.

## Tooling Landscape
- **Confluent Schema Registry:** The standard for Kafka/Avro ecosystems.
- **Apicurio Registry:** Red Hat's open-source alternative; supports Avro, Protobuf, and JSON Schema.
- **Buf:** The modern standard for Protobuf/gRPC management, focusing on "linting" schemas like code.

## Breaking Changes in Production
If you MUST make a breaking change:
1. Create a **new topic** or a new versioned endpoint (e.g., `/v2/`).
2. Run a "bridge" service that consumes from the old topic, transforms data, and publishes to the new topic.
3. Gradually migrate consumers to the new topic.

## Further Reading
- [[ApacheKafkaFundamentals]] — The primary transport for schematized data.
- [[EventDrivenArchitecture]] — Using schemas as events.
- [[ApiDesignBestPractices]] — Versioning strategies for REST and GraphQL.

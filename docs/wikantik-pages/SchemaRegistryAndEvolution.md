---
title: Schema Registry And Evolution
type: article
cluster: data-systems
status: active
date: '2026-04-25'
tags:
- schema
- avro
- protobuf
- json-schema
- compatibility
- kafka
summary: Schema registries (Confluent, Apicurio, Buf) and the compatibility rules
  that let producers and consumers evolve independently — Avro vs Protobuf vs
  JSONSchema, backward / forward / full compatibility, and the migration
  patterns that actually work.
related:
- ApacheKafkaFundamentals
- EventDrivenArchitecture
- DatabaseMigrationStrategies
- ApiDesignBestPractices
hubs:
- DataSystems Hub
---
# Schema Registry and Evolution

When producers and consumers communicate via schema-typed messages, the question "can I change the schema without breaking consumers" becomes load-bearing. A schema registry codifies the rules: schemas are versioned, compatibility is checked at registration, the contract between producer and consumer is explicit.

Without a registry, schema evolution happens through tribal knowledge ("we told the team Slack we're adding a field") and, eventually, through outages.

## What a schema registry does

Concretely:

- Stores schema versions for each subject (typically per topic / message type).
- Validates new versions against compatibility rules at registration time.
- Returns schema by ID for serialisation / deserialisation.
- Often embeds schema IDs in messages (the message carries a 4-byte schema ID; the consumer fetches the schema from the registry).

The win: producers can't ship breaking changes accidentally. The registry rejects schemas that violate compatibility.

## The big three serialisation formats

| Format | Wire size | Schema in band | Code generation | Best for |
|---|---|---|---|---|
| **Avro** | Compact | Sometimes (with schema ID) | Yes (schema → classes) | Kafka, Hadoop ecosystem; flexible schema evolution |
| **Protobuf** | Compact | No (schema separate, code generated) | Yes (.proto → classes in many languages) | gRPC, polyglot services |
| **JSON Schema** | Verbose (JSON) | No (validation only) | Sometimes | REST APIs, debuggable wire formats |

For new messaging systems on Kafka: Avro or Protobuf. Choose based on:

- Avro is more flexible at evolution (default values, named types) but requires the schema for deserialisation.
- Protobuf is more strict but simpler to use; field numbers replace named lookups.

For internal-only systems where you control all consumers, Protobuf often wins on operational simplicity.

## Compatibility modes

The four standard compatibility rules:

| Mode | Producer evolves; consumers stay | Consumer evolves; producers stay | Use when |
|---|---|---|---|
| **Backward** | New schema can read old data | — | Consumers update first; common default |
| **Forward** | — | Old schema can read new data | Producers update first |
| **Full** | New schema can read old; old schema can read new | (both) | Both can update independently |
| **None** | (no checks) | (no checks) | Don't use; defeats the purpose |

For Kafka with central registry, "backward" is the most common default — consumers update to the new schema first; once they all do, producers can publish new-schema messages.

## What changes are compatible

The rules under "backward" compatibility (Avro convention; similar for Protobuf field-numbered):

| Change | Compatible? |
|---|---|
| Add a new optional field with default | Yes |
| Add a new required field | No (consumers don't know how to fill) |
| Remove a field | Maybe (depends on whether old data has it) |
| Rename a field | No (Avro looks up by name) |
| Change a field's type | Sometimes (string → bytes ok; int → string no) |
| Reorder fields | Yes for Avro; depends for Protobuf |
| Change default value | Yes for the schema; old data unaffected |
| Add to an enum | Yes if the consumer handles unknown enum values; depends on lib |
| Remove from an enum | No |
| Convert single value to union | Sometimes |

The registry encodes these rules; you don't have to memorise them. But you do need to design schemas with evolution in mind from day one.

## Field numbering (Protobuf specifically)

Protobuf identifies fields by number, not name. Field numbers must:

- Never be reused after a field is removed (reuse causes silent corruption).
- Never be renumbered.
- Mark removed fields as `reserved` in the schema to prevent accidental reuse.

```proto
message User {
  reserved 5;  // was 'phone', removed in v2.3
  reserved "phone";  // also reserve the name
  
  string id = 1;
  string email = 2;
  string name = 3;
  // 4 was removed but not yet reserved — fix this
  string country = 6;
}
```

Field-number discipline is the load-bearing convention in Protobuf evolution. Get it wrong; corrupt data forever.

## Schema design for evolution

Practices that age well:

- **Make new fields optional with defaults.** Always. Never required.
- **Don't reuse field numbers.** Reserve.
- **Don't rename fields** — add new ones; deprecate old ones; remove later.
- **Use enums with `UNKNOWN = 0` first value.** Lets unknown values default to UNKNOWN rather than failing.
- **Wrap primitive fields you might want to make optional later.** Protobuf has `google.protobuf.StringValue` for nullable strings.
- **Avoid required fields.** Even Protobuf 2's `required` is now considered an anti-pattern; Protobuf 3 doesn't support it.
- **Version explicitly when changes are too big to evolve.** New schema, new topic, new versioned API endpoint.

## The expand-contract pattern

For changes that aren't simple add-a-field:

1. **Expand**: add the new field/structure alongside the old. Both work.
2. **Migrate consumers** to use the new field.
3. **Migrate producers** to populate only the new field (still tolerating old).
4. **Contract**: remove the old field once nobody depends on it.

Stages can take weeks to months in large orgs. Each stage is independently safe.

Example:

```proto
// v1
message Order {
  string product_id = 1;
}

// v2 expand
message Order {
  string product_id = 1;
  string sku = 2;  // new
}

// Both produced/consumed for migration period

// v3 contract (after consumers migrated)
message Order {
  reserved 1;
  reserved "product_id";
  string sku = 2;
}
```

## Schema-on-read vs schema-on-write

- **Schema-on-write** (Avro, Protobuf) — schema enforced at the producer. Bad data rejected before it lands.
- **Schema-on-read** (JSON / unstructured) — data lands; consumer interprets. Bad data lands silently.

Schema-on-write requires more upfront discipline; pays back in fewer downstream surprises.

For new systems crossing service boundaries, schema-on-write is almost always right. Schema-on-read makes sense for log-like data where flexibility outweighs validation.

## Tools

- **Confluent Schema Registry** — original; mature; ties to Kafka. Subscription product or community version.
- **Apicurio Registry** — open source; multi-protocol (Avro, Protobuf, JSON Schema). Good self-hosted option.
- **Buf Schema Registry** — Protobuf-specific; modern UX; commercial.
- **AWS Glue Schema Registry** — AWS-native; integrates with Kinesis / Glue / MSK.
- **Pulsar Schema Registry** — built into Apache Pulsar.

For a team starting with Kafka in 2026: Apicurio (self-hosted) or Confluent (managed). For Protobuf-first orgs: Buf.

## Common failure modes

**No registry.** Producers ship schema changes; consumers break. Track the time-to-detect via incidents.

**Registry but no compatibility checks.** Compatibility set to "none." Registry becomes a documentation tool, not an enforcer.

**Registry but bypass.** Some service writes without checking. Single producer breaks everyone else.

**Schema sprawl.** Hundreds of subjects, no naming convention, no ownership. Audit periodically.

**Wide compatibility but consumers don't actually handle.** Schema says "can add new fields"; consumer code crashes on unknown fields. Test: deserialise with a schema newer than your consumer's; verify graceful handling.

**Pinning consumer to specific schema version.** Now adding a backward-compatible field still requires consumer change. Avoid; consume "latest" or use schema ID embedded in message.

## Beyond messaging

The same principles apply to:

- **Database schemas.** Migrations are schema evolution; rules are similar (add nullable columns, don't rename, etc.). See [DatabaseMigrationStrategies].
- **REST API schemas.** OpenAPI specs versioned; backward-compatible changes preferred. See [ApiDesignBestPractices].
- **GraphQL schemas.** Strong schema-typing; deprecation cycle for removals.
- **gRPC services.** Protobuf rules apply.

Wherever you have a producer-consumer contract, the schema-evolution discipline pays off.

## Further reading

- [ApacheKafkaFundamentals] — Kafka as schema registry's natural habitat
- [EventDrivenArchitecture] — events as schema-typed messages
- [DatabaseMigrationStrategies] — schema evolution for storage
- [ApiDesignBestPractices] — API-level schema design

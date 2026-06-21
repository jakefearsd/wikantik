---
date: 2025-05-15T00:00:00Z
summary: Technical patterns for evolving APIs without breaking clients. Covers Semantic
  Versioning (SemVer), expansion/contraction patterns, and deprecation headers.
cluster: software-architecture
auto-generated: false
canonical_id: 01KQ0P44MA4AT91KBPZG2XDV6E
type: article
title: Backwards Compatibility Strategies
status: active
tags:
- software-architecture
- api-design
- versioning
- schema-evolution
hubs:
- BackwardsCompatibilityStrategiesHub
---

# Backwards Compatibility: API Evolution

Maintaining backwards compatibility is the discipline of allowing a system to change while ensuring that existing consumers continue to function without modification.

## 1. Defining Breaking Changes

A "Breaking Change" is any modification to the API contract that causes a consumer to fail.
*   **Structural:** Removing a field, renaming a field, or changing a data type (e.g., `Int` to `String`).
*   **Behavioral:** Changing the side effects (e.g., an endpoint that was read-only now triggers an email).
*   **Semantic:** Changing the *meaning* of data (e.g., changing a currency field from USD to EUR without a unit change).

## 2. Versioning Strategies

| Strategy | Example | Pros | Cons |
| :--- | :--- | :--- | :--- |
| **URI** | `/v1/users` | Easy to route/cache. | Pollutes URI namespace. |
| **Header** | `Accept-Version: 2` | Clean URIs. | Harder for browser testing. |
| **Media Type** | `Accept: application/vnd.v2+json` | REST purest. | Complex client implementation. |

**Expert Recommendation:** Use **URI versioning** for major architectural shifts (e.g., moving from XML to JSON). Use **Header-based versioning** for iterative data model changes.

## 3. The "Expand and Contract" Pattern

To safely remove or rename a field, follow this three-phase rollout:
1.  **Phase 1 (Expand):** Add the new field (`new_email`) to the response but keep the old field (`email`). Both fields return the same data.
2.  **Phase 2 (Migrate):** Update documentation and issue **Deprecation Headers** (e.g., `Sunset: Wed, 15 May 2025`). Encourage clients to switch.
3.  **Phase 3 (Contract):** Once telemetry shows 0% usage of the old field, remove it.

## 4. Schema Evolution: Protobuf and JSON

*   **Protobuf:** Uses **Tag Numbers** for field identification. You can rename a field in the `.proto` file as long as the tag number remains the same; the binary format is unaffected. **Rule:** Never reuse a tag number from a deleted field.
*   **JSON Schema:** Use "Open Content" models. Ensure your clients are programmed to ignore unknown fields rather than throwing an error (Standard in most JSON parsers like Jackson/Gson).

## 5. Automated Verification

*   **Consumer-Driven Contracts (CDC):** Use tools like **Pact**. Consumers provide a "contract" of what they expect. The provider runs these tests before every deployment. If a change breaks a consumer's contract, the build fails.

---
**See Also:**
- [Api Design Best Practices](ApiDesignBestPractices) — Building stable contracts.
- [Canary Deployments](CanaryDeployments) — Testing changes in isolation.
- [Developer Experience](DeveloperExperience) — Communicating changes effectively.

---
cluster: web-services-and-apis
canonical_id: 01KQ0P44KVHDX7A94TZGG20N0S
title: Api Design Best Practices
type: article
tags:
- web-services
- api-design
- rest
- idempotency
- openapi
status: active
date: 2025-05-15
summary: Technical guidelines for designing resilient and evolvable REST APIs. Covers idempotency, error modeling (RFC 7807), and versioning strategies.
auto-generated: false
---

# API Design Best Practices: Systems Integration

Resilient APIs must act as stable contracts between decoupled services, prioritizing predictability and observability.

## 1. Idempotency and State Management

Distributed systems suffer from network retries. Any operation that modifies state (POST, PUT, DELETE) must be **Idempotent**.

*   **Idempotency Keys:** Require an `X-Idempotency-Key` header (UUID) for `POST` requests. The server stores the result of the first successful request associated with that key. Subsequent requests with the same key return the cached response without re-executing business logic.
*   **Concrete Example:** In a payment API, if the client times out but the payment succeeded, a retry with the same key prevents double-billing.

## 2. Standardized Error Modeling (RFC 7807)

Avoid generic `500 Internal Server Error` responses. Use **Problem Details for HTTP APIs**.

*   **Response Structure:**
    ```json
    {
      "type": "https://api.example.com/probs/insufficient-funds",
      "title": "Insufficient Funds",
      "status": 403,
      "detail": "Account balance is $10.00, but the requested amount is $50.00.",
      "instance": "/account/123/transactions/abc"
    }
    ```
*   **Benefit:** This provides machine-readable error codes while allowing for human-readable debugging info, integrated into standard monitoring tools.

## 3. Resource-Oriented Design

*   **Nouns over Verbs:** Use `/orders/{id}` (GET/DELETE) instead of `/getOrder` or `/deleteOrder`.
*   **Sub-resources:** Nesting should not exceed two levels deep: `/users/{id}/orders`. For more complexity, use filtering: `/orders?user_id={id}`.
*   **Pagination:** Use **Cursor-Based Pagination** for high-velocity datasets. Offset-based pagination (`?page=2`) is subject to "drifting" where items are skipped or duplicated if data is added between requests.

## 4. Documentation and the OpenAPI Spec

The **OpenAPI Specification (OAS)** is the single source of truth.
*   **Contract-First Development:** Write the OAS file *before* coding. Use tools like `prism` to mock the API for frontend developers immediately.
*   **Validation:** Use middleware (e.g., `express-openapi-validator`) to automatically reject requests that do not match the spec, ensuring the code never drifts from the documentation.

---
**See Also:**
- [Api Security Patterns](ApiSecurityPatterns) — Rate limiting and Auth.
- [Backwards Compatibility Strategies](BackwardsCompatibilityStrategies) — Managing version drift.
- [Developer Experience](DeveloperExperience) — Onboarding and SDK generation.

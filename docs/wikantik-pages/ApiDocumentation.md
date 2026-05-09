---
cluster: web-services-and-apis
canonical_id: 01KQ0P44KW9SGE9EK524404D0X
title: API Documentation
type: article
tags: [openapi, documentation-as-code, swagger, api-design]
summary: Technical guide to API documentation using OpenAPI 3.1.0 and Documentation-as-Code workflows for automated contract enforcement.
auto-generated: false
date: 2025-05-15
---

# API Documentation

Effective API documentation has evolved from static README files to machine-executable contracts. In a **Documentation-as-Code** workflow, the API specification is treated with the same rigor as source code, residing in version control and driving automated testing and client generation.

## OpenAPI 3.1.0 Specification

The OpenAPI Specification (OAS) 3.1.0 is the current industry standard. It achieves full alignment with JSON Schema (Draft 2020-12), enabling complex validation rules and better tooling interoperability.

### Key Components of OAS 3.1.0
*   **`info`:** Metadata including versioning and license information.
*   **`servers`:** Array of base URLs for different environments (Development, Staging, Production).
*   **`paths`:** The core routing logic, mapping HTTP verbs to operations.
*   **`components/schemas`:** Reusable data models defined using JSON Schema.
*   **`webhooks`:** (New in 3.1.0) Documentation for out-of-band callbacks.

### Example: Technical SOP Retrieval Endpoint
```yaml
openapi: 3.1.0
info:
  title: Wikantik SOP API
  version: 1.2.0
paths:
  /v1/sop/{sopId}:
    get:
      summary: Retrieve a specific SOP by its ID.
      parameters:
        - name: sopId
          in: path
          required: true
          schema:
            type: string
            format: ulid
      responses:
        '200':
          description: Successful retrieval.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SOP'
components:
  schemas:
    SOP:
      type: object
      required: [id, title, status]
      properties:
        id: { type: string, format: ulid }
        title: { type: string, minLength: 5 }
        status: { type: string, enum: [draft, active, archived] }
```

## Documentation-as-Code Workflow

The Documentation-as-Code (DaC) paradigm integrates documentation into the CI/CD pipeline to prevent "Contract Drift."

1.  **Design-First:** Write the OAS file before implementation. This allows frontend and backend teams to work in parallel against a stable mock.
2.  **Linting:** Use tools like `spectral` to enforce style guides (e.g., mandatory descriptions, kebab-case paths).
3.  **Contract Testing:** Use tools like `Pact` or `Dredd` to verify that the implementation actually adheres to the OAS file.
4.  **Automated Generation:** 
    *   **UI:** Render interactive docs via `Swagger UI` or `Redoc`.
    *   **SDKs:** Generate client libraries using `openapi-generator`.

## Check-Act-Verify for API Docs

| Phase | Check (Pre-condition) | Act (Execution) | Verify (Post-condition) |
| :--- | :--- | :--- | :--- |
| **Commit** | Run `spectral lint` on the YAML file. | Update path logic or schema definition. | Confirm 0 linting errors. |
| **Build** | Verify file exists in `docs/api/`. | Generate static HTML via `Redoc CLI`. | Validate that all examples render correctly. |
| **Deploy** | Compare OAS version vs. binary version. | Publish docs to a central developer portal. | Run a smoke test against the live `/swagger.json`. |

## High-Cardinality Attribute Indexing in Docs
When documenting APIs with high-cardinality fields (e.g., `user_id`, `trace_id`), the documentation must explicitly state the **indexing strategy**:
*   **Filterable:** Attributes that are indexed and support range/equality queries.
*   **Searchable:** Attributes that support full-text search.
*   **Opaque:** Metadata that is stored but not searchable.
This clarity allows consumers to design efficient queries without guessing the backend performance characteristics.

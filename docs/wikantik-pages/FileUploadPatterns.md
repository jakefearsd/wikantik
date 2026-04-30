---
canonical_id: 01KQ0P44QD0AQWD7SJQPX43XT1
title: File Upload Patterns
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How to handle file uploads in modern APIs — multipart, signed URLs, resumable
  uploads, and the patterns that scale to large files and unreliable networks.
tags:
- file-upload
- api
- s3
- signed-urls
- multipart
related:
- ApiProtocolComparison
- IdempotencyPatterns
- BatchApiDesign
hubs:
- WebServicesAndApisHub
---
# File Upload Patterns

File uploads are deceptively complex. A small file via multipart form is straightforward. A 10 GB video, a flaky mobile connection, a multi-tenant system — each adds requirements. The right pattern depends on file size, reliability needs, and infrastructure.

This page covers the patterns that scale.

## Multipart form upload

The classic browser pattern:

```http
POST /api/upload
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary

------WebKitFormBoundary
Content-Disposition: form-data; name="file"; filename="report.pdf"
Content-Type: application/pdf

(binary data)
------WebKitFormBoundary--
```

Server parses the multipart envelope, extracts the file, processes it.

**When to use**: small files (under ~10 MB), stable connections, simple use cases. Browser default for `<form enctype="multipart/form-data">`.

**Limitations**: full file in one request; failure means starting over; ties up application server during upload; server must handle untrusted content.

## Signed URLs (the modern default)

The application server generates a time-limited URL for direct upload to object storage (S3, GCS, Azure Blob). The client uploads directly to storage; the application server is bypassed for the bulk transfer.

```
Client → POST /api/upload-init → Server
   (returns: { signed_url: "https://s3...", file_id: "abc" })
Client → PUT https://s3... (binary data) → S3
Client → POST /api/upload-complete?file_id=abc → Server
```

**Advantages**:
- Application server doesn't handle large file traffic
- Object storage is purpose-built for this; scales naturally
- Reduces server cost and complexity
- The application server only sees small metadata requests

**When to use**: most production file uploads. Default for files larger than a few MB.

## Resumable uploads

For large files or unreliable networks, resumable protocols allow continuing an interrupted upload.

### S3 multipart upload

The client splits the file into parts, uploads each separately, and signals completion. Failed parts can be re-uploaded; the client tracks which parts succeeded.

```
1. POST initiate-multipart-upload → upload_id
2. PUT part 1 with upload_id and part number
3. PUT part 2 with upload_id and part number
   ... (parts can be parallel)
4. POST complete-multipart-upload → final object
```

S3 multipart uploads support parts up to 5 GB; a single upload can be terabytes.

### Tus protocol

Open protocol for resumable uploads. The client tracks the offset; the server reports how much has been received. Either side can resume after interruption.

Used by Vimeo, Cloudflare, others. Less common than S3 multipart in cloud-native stacks.

### Chunked uploads with HTTP

Browser-native streaming via `fetch` with a ReadableStream body. Less standardized; works with HTTP/2 well.

## Validation

Don't trust uploaded files. Always:

1. **Verify content type.** Check the file content matches the declared type (magic bytes, not just extension).
2. **Limit size.** Enforce max-file-size at multiple layers (load balancer, application, storage).
3. **Scan for malicious content.** ClamAV or cloud equivalents for known-bad files.
4. **Validate per use case.** Image processing? Verify it's a valid image. Document upload? Strip macros from Office files.
5. **Quarantine until validated.** Store in a quarantine bucket; move to permanent storage after validation.

## Storage and naming

After upload:

- **Generate IDs server-side.** Don't use client-provided filenames as keys.
- **Store original filename separately** if you need to preserve it for display.
- **Avoid hot-spotted keys.** Sequential IDs concentrate on a single S3 partition; UUIDs distribute.
- **Set appropriate content-type metadata.** Affects how downloads serve.

## Common security issues

- **Path traversal**: client sends `filename=../../etc/passwd`; server writes outside intended directory. Always sanitize filenames.
- **MIME confusion**: file claims to be `image/png` but is HTML; browser interprets it as HTML and runs scripts. Use Content-Disposition or strict MIME enforcement.
- **Unrestricted upload**: attacker uploads malware; legitimate user downloads it. Validate and scan.
- **Storage exhaustion**: unlimited uploads fill disk. Quotas at user, tenant, system level.

## Common patterns to avoid

- **Application server proxying large file transfers.** Slow; expensive; unnecessary.
- **Storing files in the database.** PostgreSQL bytea works but is rarely the right choice. Use object storage.
- **Synchronous large-file processing on upload.** Blocks the request. Process async after upload completes.
- **Trusting the client-provided content type.** Verify with magic-byte detection.

## Common failure patterns

- **Memory exhaustion** on the server processing large uploads. Stream; don't buffer.
- **Frontend timeouts** during long uploads. Use progress reporting; consider resumable protocols.
- **Forgetting cleanup** of incomplete multipart uploads. They accumulate; configure lifecycle rules.
- **Missing virus/content scanning.** Liability for hosting malware.
- **Clear-text uploads.** Must use TLS; sensitive files should be encrypted at rest.

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — Protocol context
- [IdempotencyPatterns](IdempotencyPatterns) — Retry semantics for upload
- [BatchApiDesign](BatchApiDesign) — Bulk operations adjacent
- [WebServicesAndApis Hub](WebServicesAndApisHub) — Cluster index

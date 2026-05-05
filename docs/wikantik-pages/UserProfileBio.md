---
status: official
cluster: wikantik-development
type: article
title: User Profile Bio
date: '2026-05-04'
summary: A feature overview of the Markdown-enabled user biography system in Wikantik.
canonical_id: 01KQTD4FHACTTQ9QSQ699X37ZM
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# User Profile Bio

Introduced on **April 3, 2026**, the User Profile Bio feature allows users to provide a rich-text self-description that is displayed on their profile page and accompanying their contributions.

## Purpose
The bio serves to:
- **Build Trust:** In a collaborative wiki, knowing the expertise and background of an author helps readers evaluate the content.
- **Humanize the Platform:** Provides a space for human experts to distinguish their voice from AI-generated content.
- **Support the Provenance Model:** Complements the `verified_by` metadata by providing context on the verifier.

## Implementation Detail

### Storage
User bios are stored in the `users` table within the PostgreSQL database. The schema includes a `bio` column (TEXT) that supports CommonMark Markdown.

### API Access
The bio is exposed through the following endpoints:
- `GET /api/user/profile/{username}`: Returns the full user profile, including the rendered bio.
- `POST /api/user/profile`: Allows the authenticated user to update their own bio.

### UI Integration
In the React SPA (`wikantik-frontend`), the bio is rendered using the standard wiki Markdown components, ensuring consistent styling with the rest of the wiki content. It appears in the **User Profile** view and as a hover-card on author links in page footers.

## See Also
- [Relational User Database](RelationalUserDatabase) — The underlying PostgreSQL schema for users and groups.
- [Wikantik Development](WikantikDevelopment) — The feature timeline for the platform.

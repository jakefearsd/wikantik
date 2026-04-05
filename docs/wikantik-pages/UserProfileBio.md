---
summary: Bio field on user profiles for personal descriptions
tags:
- development
- user-profile
- ui
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-03'
related:
- WikantikDevelopment
---
# User Profile Bio

The user profile bio feature adds a free-text biography field to user profiles. Users can write a short description of themselves that is displayed on their profile page.

## Implementation

The bio field is stored in the `users` table and editable through the user preferences page in the React SPA. The field supports plain text with a reasonable character limit.

[{Relationships}]

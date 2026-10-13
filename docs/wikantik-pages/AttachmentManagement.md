---
title: Attachment Management
date: '2026-04-03'
cluster: wikantik-development
status: active
type: article
depends-on:
- JspToReactMigration
canonical_id: 01KQ0P44M4DND319VRMF10RT6Z
related:
- WikantikDevelopment
- JspToReactMigration
summary: 'Uploading, listing, renaming and deleting page attachments: editor paste/drop
  and panel UI, /api/attachments endpoints, naming rules and upload limits.'
tags:
- development
- attachments
- ui
- file-management
---
# Attachment Management

Attachments are files stored with a page. In the React editor an author adds one by pasting or dropping it into the text, or from the Attachments panel, and the editor inserts the Markdown reference automatically.

## Features

- **Paste or drag-and-drop upload in the editor.** Files dropped or pasted into the editor upload one at a time. A placeholder `![Uploading name…]()` is inserted at once and replaced by the real markup when the upload finishes (or removed, with an error toast, if it fails). On a new page that has not been saved yet, the placeholders go in immediately and the uploads start after the first save. A pasted clipboard image is named `pasted-YYYYMMDD-HHMMSS.<ext>`.
- **Attachments panel.** A file picker with an editable name field that is validated as you type; the page's attachment list with an image preview (the browser loads the stored file from `/attach/{Page}/{file}`, the server does not generate thumbnails); rename; and delete, which asks for confirmation and shows how many times the editor text still references the file. The panel also holds an *Ingest as derived page* form (`POST /api/ingest`) that turns an uploaded document into a page; that is a separate feature from attachments.
- **Inline Markdown references.** Dragging a row from the panel into the editor inserts `![name](file.png)` for an image or `[name](file.pdf)` for anything else. A pasted or dropped upload inserts the same markup. The reference is the bare file name, which resolves to the page's own attachment.
- **Filename normalisation.** For a pasted or dropped file the editor cleans the name to fit the rules below (spaces and inner periods become `-`, other disallowed characters are dropped, the name is shortened) and adds `-2`, `-3` and so on when it collides with an existing attachment. The server enforces the same rules and refuses anything else.

## Naming rules

`AttachmentNameValidator` (server) and `attachmentNameValidator.js` (editor) accept a name only if it uses just `a-z`, `A-Z`, `0-9`, `_` and `-` plus exactly one period, starts with a letter or digit, is at most 40 characters, and has no `-` or `_` directly before the period. When an upload sends a `name`, its extension must match the uploaded file's.

## Permissions and upload limits

- Listing and downloading need page `view`; uploading needs `upload`; renaming needs `edit`; deleting needs `delete`. `modify` implies `upload`, so signed-in users can upload by default and anonymous visitors cannot, while only administrators can delete (see [Database-Backed Permissions](DatabaseBackedPermissions)).
- Operators can restrict uploads with `wikantik.attachment.maxsize` (bytes), `wikantik.attachment.allowed` and `wikantik.attachment.forbidden` (space-separated suffixes such as `.png .jpg`). All three ship blank, which means no size limit and every file type accepted, so set them wherever uploads are not limited to trusted users. A rejected upload gets HTTP 413 (too large) or 415 (type not allowed). Administrators are exempt. The same policy applies to the REST endpoint and to the legacy `/attach/*` upload servlet.
- Links to `.html`, `.htm`, `.js`, `.pdf`, `.svg` and `.xml` attachments force a download instead of opening inline (`wikantik.attachment.forceDownload`).
- Files are kept by `BasicAttachmentProvider`, a flat versioning store under `wikantik.basicAttachmentProvider.storageDir` (blank means a `wikantik-files` directory in the home folder of the user running Tomcat). Leaving `wikantik.attachment.provider` blank disables attachments.

## Implementation

The REST servlet `AttachmentResource` (`wikantik-rest`) is mapped to `/api/attachments/*`; the React components are `AttachmentPanel.jsx` and `PageEditor.jsx`; storage goes through `AttachmentManager` in `wikantik-main` and the provider abstraction. Inline images and downloads are served by `AttachmentServlet` at `/attach/{Page}/{file}`.

| Request | Purpose |
|---|---|
| `GET /api/attachments/{Page}` | list attachments (name, size, version, last modified) |
| `GET /api/attachments/{Page}/{file}` | download one |
| `POST /api/attachments/{Page}` | multipart upload: part `file`, optional `name` |
| `PUT /api/attachments/{Page}/{old}` | rename, JSON body `{"newName": "..."}` |
| `DELETE /api/attachments/{Page}/{file}` | delete |

[{Relationships}]

# Attachment Management Design

**Date:** 2026-04-03
**Status:** Approved

## Overview

A robust attachment management system for wiki pages and blog entries. Authors upload, name, rename, and delete attachments through a slide-in panel in the editor. Attachments are referenced in markdown using simple relative filenames (`![alt](beach.jpg)`, `[label](report.pdf)`), and the system transparently resolves these to the correct serving URLs. Authors never see or care about the physical storage path.

## Goals

- Authors experience attachments as living "next to" their content
- Every attachment gets a deliberate, author-chosen name
- Drag-and-drop from the attachment panel into the editor inserts correct markdown
- Rename updates markdown references automatically
- Works identically for wiki pages and blog entries
- Client-side preview resolves attachment references without server round-trips
- Server-side rendering continues to work as-is with targeted hardening

## Non-Goals

- OS file drag-and-drop directly onto the editor textarea (future enhancement)
- Subdirectories or folders within a page's attachments
- Shared attachment libraries across pages
- File-type icons in rendered output
- Batch upload with multi-file naming

---

## 1. Filename Validation Rules

All attachment filenames are validated at both the client (immediate feedback) and server (authoritative enforcement):

- **Allowed characters:** `a-zA-Z0-9._-` only
- **Maximum length:** 40 characters including extension
- **Must contain exactly one period** separating the stem from the extension
- **No leading or trailing** periods, hyphens, or underscores
- **Extension must match** the uploaded file's original extension (case-insensitive)

Invalid filenames are rejected with HTTP `400` and a clear error message.

---

## 2. REST API Changes

### Enhanced: `POST /api/attachments/{page}`

Upload an attachment with a user-chosen name.

**Request:** Multipart form data with:
- `file` (required) — the file content
- `name` (required) — the desired filename, e.g. `beach.jpg`

**Validation:**
- `name` must pass the filename validation rules
- The extension of `name` must match the extension of the uploaded file's original filename (case-insensitive). E.g., uploading `IMG_20260403.jpg` with name `beach.jpg` is allowed; name `beach.png` is rejected.
- If an attachment with that name already exists, a new version is created (existing provider behavior).

**Response:** `200` with attachment metadata JSON (name, size, version, lastModified).

### New: `PUT /api/attachments/{page}/{oldName}`

Rename an attachment.

**Request body:** `{ "newName": "sunset.jpg" }`

**Validation:**
- `newName` must pass filename validation rules
- Extension of `oldName` and `newName` must match
- `oldName` must exist as an attachment on the page

**Implementation:** Download attachment data from `oldName`, store under `newName`, delete `oldName`. This avoids adding new methods to the `AttachmentProvider` interface.

**Response:** `200` with the new attachment metadata.

### Unchanged

- `GET /api/attachments/{page}` — list attachments for a page
- `GET /api/attachments/{page}/{name}` — download an attachment
- `DELETE /api/attachments/{page}/{name}` — delete an attachment

---

## 3. Attachment Panel UI

### Trigger

A toolbar button (paperclip icon) in both `PageEditor` and `BlogEditor` toggles a right-side slide-in panel. The panel sits alongside the editor so both are visible simultaneously, enabling drag-and-drop.

### Panel Layout

```
┌──────────────────────────────────┐
│  Attachments for "My Page"  [X]  │
├──────────────────────────────────┤
│                                  │
│  Upload                          │
│  [Choose File] IMG_20260403.jpg  │
│  Name: [beach         ][.jpg]    │
│                       [Upload]   │
│                                  │
│  Attachments                     │
│  [thumb] beach.jpg   45 KB [R][D]│
│  [icon]  report.pdf 1.2 MB [R][D]│
│  [thumb] banner.png  120 KB [R][D]│
│                                  │
│  Drag items into the editor      │
└──────────────────────────────────┘
```

### Upload Section

- File picker populates the Name field with the original filename stem as default
- Extension is shown but not editable — derived from the original file
- Client-side validation runs on the name field before enabling the Upload button
- On success, the new attachment appears in the list

### Attachment List

- Image attachments show a small thumbnail preview; non-images show a generic file-type icon
- Each row is draggable (see Section 4)
- **Rename (R):** Opens an inline text field for the stem; extension locked. On confirm, calls the rename API and updates markdown references in the editor (see Section 5)
- **Delete (D):** If the filename appears in markdown image/link syntax in the editor, the confirmation dialog warns about broken references. On confirm, calls the delete API.

### Component Structure

- `AttachmentPanel` — the slide-in panel shell, receives hook state and callbacks
- `AttachmentUploadForm` — file picker, name input, upload button
- `AttachmentList` — the scrollable list of current attachments
- `AttachmentRow` — individual row: thumbnail/icon, name, size, rename/delete actions

---

## 4. Drag-and-Drop from Panel to Editor

### Mechanism

Uses the HTML5 Drag and Drop API via `dataTransfer`.

### Drag Start (in `AttachmentRow`)

On `dragstart`, set `dataTransfer` with:
- `text/plain` — the markdown to insert:
  - Images: `![beach](beach.jpg)` (alt text defaults to filename stem)
  - Non-images: `[report](report.pdf)` (link text defaults to filename stem)
- `application/x-wikantik-attachment` — JSON `{ "name": "beach.jpg", "isImage": true }` for potential richer handling

### Drop Target (editor textarea)

The editor textarea registers `dragover` (to allow drop) and `drop` event handlers.

On `drop`:
1. Read `text/plain` from `dataTransfer`
2. Determine cursor position at drop point via `document.caretPositionFromPoint()` (with `caretRangeFromPoint` fallback)
3. Insert the markdown text at that position in the editor's content state
4. Preview re-renders automatically

### Edge Cases

- Drop outside the textarea: no-op (default browser behavior)
- Modal positioning: the slide-in panel sits to the right, leaving the editor visible — no repositioning needed

---

## 5. Rename and Reference Update Logic

### Rename Flow

1. User clicks rename on `beach.jpg`
2. Inline field shows `beach` (editable) + `.jpg` (locked)
3. User types `sunset`, confirms
4. Client validates `sunset.jpg`
5. Client calls `PUT /api/attachments/{page}/beach.jpg` with `{ "newName": "sunset.jpg" }`
6. Server copies data to `sunset.jpg`, deletes `beach.jpg`, returns metadata
7. Client updates `attachmentList` state
8. Client scans editor markdown and replaces references only within image/link syntax:
   - `![anything](beach.jpg)` → `![anything](sunset.jpg)`
   - `[anything](beach.jpg)` → `[anything](sunset.jpg)`
   - Freeform text mentions of `beach.jpg` are left untouched
9. Preview re-renders

### Reference Replacement Regex

```
(!\[[^\]]*\])\(beach\.jpg\)  →  $1(sunset.jpg)
(\[[^\]]*\])\(beach\.jpg\)   →  $1(sunset.jpg)
```

Matches standard markdown image/link syntax only. Intentionally simple — exotic nesting or escaping is not a concern for normal authoring.

### Delete Flow

1. User clicks delete on `beach.jpg`
2. Client checks if `beach.jpg` appears in any `![...](beach.jpg)` or `[...](beach.jpg)` in editor content
3. If found: confirmation warns "This attachment is referenced in your content. Deleting it will leave broken references. Continue?"
4. If not found: simple "Delete beach.jpg?" confirmation
5. On confirm: `DELETE /api/attachments/{page}/beach.jpg`, update list, preview shows broken-reference placeholders

---

## 6. Client-Side Preview Image Resolution

### Approach

A custom remark plugin for `ReactMarkdown` that resolves relative attachment references in the editor's live preview.

### Resolution Logic

The editor maintains an `attachmentList` state — fetched on mount, updated after every upload/rename/delete.

For each `![alt](url)` and `[text](url)` node in the markdown AST:

1. If `url` is absolute (starts with `http://`, `https://`, `/`) — leave unchanged
2. If `url` matches a filename in `attachmentList` — rewrite to `/attach/{pageName}/{filename}`
3. If `url` is relative but not in `attachmentList` — render a broken-reference placeholder

### Broken-Reference Placeholders (editor preview only)

- **Images:** A `<div>` with red dashed border, containing the filename and "not found" text
- **Links:** Link text renders normally but with red underline and a tooltip "attachment not found"

These placeholders exist only in the client-side preview. The server-side rendered output uses the browser's default broken-image behavior.

---

## 7. Server-Side Rendering Hardening

The existing `WikantikLinkNodePostProcessor` already resolves attachment references. Targeted hardening:

### Case-Insensitive Lookup

When checking if a relative reference is an attachment, perform case-insensitive matching against the page's attachment list. If the author writes `Beach.JPG` but the file is `beach.jpg`, it should resolve.

### Path Traversal Prevention

Explicitly reject any reference containing `..` or `/` before performing attachment lookup. This is a security boundary — the `AttachmentManager` lookup may handle this implicitly, but an explicit guard makes the intent clear.

### Skip Invalid Filenames

If a relative reference doesn't match the filename validation rules (non-allowed characters, too long, no extension), skip the attachment lookup entirely. This avoids unnecessary provider calls for references that can't possibly be attachments.

### URL-Encoded References

Normalize URL-encoded references before lookup. `my%20photo.jpg` should match if the attachment is stored as `my photo.jpg`. Note: with our strict filename rules (no spaces allowed), this is mostly a defensive measure — valid attachment names won't contain spaces. But the normalization ensures robustness if rules change.

### No Output Changes

Resolved URLs remain `/attach/PageName/filename.ext`. No new HTML attributes or wrapper elements.

---

## 8. Shared Editor Integration

### Custom Hooks

**`useAttachments(pageName)`** — manages attachment state and API calls:
- `list` — current attachment array (name, size, version, lastModified, isImage)
- `uploadAttachment(file, name)` — calls API, updates list on success
- `renameAttachment(oldName, newName)` — calls API, updates list, returns old/new names for reference replacement
- `deleteAttachment(name)` — calls API, updates list
- `isValidFilename(name)` — client-side validation against the filename rules
- `loading` / `error` state

**`useEditorDrop(textareaRef)`** — registers HTML5 drag-and-drop handlers on the textarea:
- `dragover` handler to allow drops
- `drop` handler that reads `text/plain` from `dataTransfer` and inserts at the drop point
- Returns nothing — side-effect only hook

### Editor Integration

Both `PageEditor.jsx` and `BlogEditor.jsx` add:
- `useAttachments(pageName)` hook call
- `useEditorDrop(textareaRef)` hook call
- Toolbar button to toggle the `AttachmentPanel`
- Pass `attachments.list` to the preview's remark plugin for resolution
- Wire rename callback to perform reference replacement on editor content

No changes to how pages are saved — markdown content is plain text, attachment management is a side-channel.

### Page Name for Blog Entries

Blog entries use their full path as the page name (e.g., `blog/admin/20260403AnotherBlobPost`). The `useAttachments` hook receives this from the editor, which already knows the page name. Each blog entry owns its attachments independently.

---

## 9. Testing Strategy

### Unit Tests (Java)
- Filename validation logic — valid names, invalid characters, length limits, extension mismatch
- Rename endpoint — successful rename, extension mismatch rejection, not-found handling
- Upload with name field — name applied correctly, validation enforced
- Server-side rendering hardening — case-insensitive lookup, path traversal rejection, URL-encoded normalization

### Unit Tests (JavaScript)
- `useAttachments` hook — upload/rename/delete update state correctly, validation logic
- `useEditorDrop` hook — markdown insertion at correct position
- Remark plugin — resolves known attachments, leaves absolute URLs alone, renders placeholders for missing
- Reference replacement regex — updates only markdown syntax, not freeform text

### Integration Tests
- Upload → reference in markdown → save → rendered page shows image
- Upload → rename → markdown references updated → save → rendered correctly
- Upload → delete with warning → broken reference placeholder in preview
- Drag from panel → correct markdown inserted at drop point

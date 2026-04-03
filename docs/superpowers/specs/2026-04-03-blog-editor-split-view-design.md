# New Blog Entry: Side-by-Side Editor with Preview

## Context

The current `NewBlogEntry.jsx` is a plain form with a topic name input and a textarea — no live preview, no frontmatter visibility. The existing `PageEditor` and `BlogEditor` components already implement a side-by-side markdown editor with live preview using `ReactMarkdown` + `remarkGfm` and shared CSS classes. This change brings the new-entry creation form to parity with the editing experience.

## Design

### Layout

```
┌──────────────────────────────────────────────────────┐
│              [  Title Input (centered)  ]             │
│              "Becomes the entry page name"            │
├─────────────────────────┬────────────────────────────┤
│  Markdown Editor        │  Live Preview              │
│  (monospace textarea)   │  (ReactMarkdown + GFM)     │
│                         │                            │
│                         │                            │
├─────────────────────────┴────────────────────────────┤
│                            [Cancel]  [Create Entry]  │
└──────────────────────────────────────────────────────┘
```

- **Title input**: Centered above the split view. Text field, not part of the editor textarea. Derives the `topic` parameter for the API call (spaces stripped, as today).
- **Editor pane**: Shows only body markdown — no frontmatter. Uses `editor-textarea` class.
- **Preview pane**: Live client-side rendering via `ReactMarkdown` with `remarkGfm`. Updates on every keystroke (same as PageEditor). Uses `editor-preview` class.
- **Responsive**: On mobile (<768px), stacks to single column via existing media query on `editor-container`.

### What changes

**`NewBlogEntry.jsx`** — rewrite the form:
- Replace the plain form with the split editor layout
- Reuse CSS classes: `editor-container`, `editor-pane`, `editor-textarea`, `editor-preview`
- Add `ReactMarkdown` + `remarkGfm` imports (already dependencies)
- Title input centered above the editor container
- Submit sends `{ topic: title.replace(/\s+/g, ''), content: body }` to `api.blog.create()`
- Validation: title cannot be blank (same as today)

### What does NOT change

- **Backend**: `BlogManager.createEntry(session, topic, content)` already accepts initial content and auto-generates frontmatter (title, date, author)
- **REST API**: `POST /api/blog/{username}/entries` already accepts `{ topic, content }`
- **CSS**: All editor layout classes already exist in `globals.css`
- **BlogEditor.jsx**: Unchanged — still handles editing existing entries
- **No new endpoints, no new CSS, no new dependencies**

## Verification

1. Build frontend: `cd wikantik-frontend && npm run build`
2. Deploy locally and navigate to blog > "New Entry"
3. Confirm: title input centered, side-by-side editor + preview visible
4. Type markdown in editor — preview updates live
5. Create an entry — verify it saves with correct title/content
6. Verify mobile layout stacks to single column (resize browser < 768px)
7. Run existing tests: `mvn test -pl wikantik-main -Dtest=DefaultBlogManagerTest`

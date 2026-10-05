# Creating and Editing Pages

This guide is for people who write and edit wiki pages in the web editor. It covers creating a page, the editor's toolbar and keyboard shortcuts, live preview, link completion, attachments, drafts, the frontmatter form, validation messages and page access rules. In every shortcut below, **Mod** means Ctrl on Windows and Linux and Cmd on a Mac.

You can edit a page only when the page view shows an **Edit** button. If it does not, your account lacks the `edit` permission on that page.

## Create a page

There are four ways to start a new page:

- Click **+ New Article** in the sidebar (shown when you are signed in).
- Press **Mod+O** (or **Mod+K** when the cursor is not in the editor), type the title, and choose **Create page "..."**.
- Press **Mod+P**, type `new page` and run **New page**.
- Click a link to a page that does not exist yet.

The **New Article** dialog (`NewArticleModal.jsx`) asks for:

| Field | Notes |
|-------|-------|
| Title | Free text. The page name is filled in from it as CamelCase (`Berlin: History & Culture` becomes `BerlinHistoryAndCulture`). |
| Page Name | The permanent URL name. Letters and digits only, up to 100 characters. Edit it to override the generated name; it cannot be changed here after creation. A name that already exists shows "This page already exists", and the button becomes **Open Editor**. |
| Cluster | Optional, except for a hub, where it is required. The box suggests existing clusters. |
| Type | One button per template: Article, Reference, Design, Runbook, Hub. Hover for a description. |

Below the buttons a preview shows the starting text for the chosen type. Click **Create** to open the editor with that template and its frontmatter filled in. Nothing is saved until you click **Save**. The templates come from `GET /api/page-templates`; if the request fails the dialog shows "Templates unavailable" and falls back to a plain `# Title` body.

## Edit a page

The editor page has these parts from top to bottom:

1. A header with the **Change note…** box, **Attach**, **Outline**, **Cancel** and **Save**.
2. The **Frontmatter** and **Knowledge** tabs (see [Edit the page's metadata](#edit-the-pages-metadata)).
3. The formatting toolbar.
4. The Markdown source on the left and a rendered preview on the right, with a side rail (outline, backlinks, unlinked mentions) at the far right.
5. A status bar showing word count, reading time and the cursor line and column.

Type a short **Change note…** to describe the edit. It is stored with the version and shown in the page's history. If you leave it blank the note is "Created page" or "Updated page".

Click **Save** or press **Mod+S**. A successful save returns you to the page view with a "Saved" message. Warnings do not stop a save: you see "Saved with N advisory warning(s)" or "Saved with N math warning(s)". **Cancel** returns to the page view; if you have unsaved changes the wiki first asks whether to leave.

### Toolbar and keyboard shortcuts

The toolbar buttons and shortcuts all run entries from one command registry (`commands/registry.js`, `utils/editorCommands.js`), so they match the command palette.

| Toolbar button | Command | Shortcut |
|----------------|---------|----------|
| **B** | Bold | Mod+B |
| **I** | Italic | Mod+I |
| **H** | Heading (level 2) | none |
| **≡** | Bulleted list | none |
| `` ` `` | Inline code | none |
| **{ }** | Code block | none |
| **▦** | Table | none |
| link icon | Insert link | Mod+K |
| **Live** | Toggle live preview | Mod+E |

Other keys bound while you edit:

| Keys | Action |
|------|--------|
| Mod+S | Save the page |
| Ctrl+Alt+[ | Fold all headings |
| Ctrl+Alt+] | Unfold all headings |
| Ctrl (Cmd) + hover over a link | Show a preview card for the link |
| Ctrl (Cmd) + click a link | Open it in a new tab |
| Esc | Close an open preview card, or close the outline rail on a narrow window |

Mod+K inserts a link while the cursor is in the editor. Outside the editor it opens the page switcher; see [Search.md](Search.md#jump-to-a-page-or-run-a-command). Mod+O, Mod+P and Mod+Alt+N work everywhere, including in the editor.

Mod+E does nothing while a dialog is open. It is disabled for pages still written in the legacy wiki markup.

The command palette (Mod+P) also lists commands with no key: Heading 1, 2 and 3, five callouts, Insert table, Code block, Math block, Horizontal rule, Insert image, **Toggle preview** (hide or show the preview pane) and **Toggle rail**.

### Insert blocks with the slash menu

Type `/` at the start of a line or after a space to open a menu of blocks. Keep typing to narrow it (for example `/tab`), press Up and Down to choose, and press Enter to insert. The menu offers Heading 1 to 3, callouts (Note, Tip, Info, Warning, Danger), Table, Code block, Math block, Horizontal rule, Image and Link. It does not open inside frontmatter, code, a URL or math.

### Live preview

Press **Mod+E** or click **Live** to render Markdown inside the editor itself: headings, emphasis, links, lists, quotes, images, math, callouts and page embeds appear formatted while you type, and the text underneath is unchanged. Press it again to return to plain source. The choice is remembered in your browser (key `wikantik.editor.mode`) and defaults to source. Live preview works only on Markdown pages.

The rendered pane on the right is separate. It re-parses your text as you type and scrolls with the editor.

### Link to other pages while you type

| You type | The editor offers |
|----------|-------------------|
| `[[` plus a few letters | Pages that match, plus **Link to new page: Name** when no page has that name. Choosing one inserts `[[Name]]`. |
| `[[Page#` | The headings of `Page`. Choosing one inserts `[[Page#Heading]]`. |
| `](` after a link label | Pages and this page's attachments. |
| `](Page#` or `](#` | Headings of `Page`, or of the page you are editing. |

Spaces are ignored when matching because page names are CamelCase. See [Linking.md](Linking.md) for every link form.

### Find unlinked mentions

The side rail has an **Unlinked mentions** section. It scans your text for phrases that match the title or alias of other pages and lists each one with the line it is on. Click **Link** to wrap the phrase as `[phrase](Target)`, **Ignore** to dismiss it, or the line excerpt to jump to it. The section reads "Mentions available shortly" while the title index warms up, and offers **Retry** if the scan fails. The scan calls `POST /api/mentions/scan`.

The same rail lists the page's **Outline** (headings up to level 4, click to jump) and its **Backlinks**.

### Add attachments

Three ways to attach a file:

- Paste an image from the clipboard into the editor.
- Drag files onto the editor.
- Click **Attach** and use the panel's **Upload file** control.

A pasted or dropped file is inserted as a placeholder (`![Uploading name…]()`) and replaced with `![name](name.png)` for an image or `[name](name.pdf)` for other files when the upload finishes. If an upload fails you see "Upload of NAME failed" and the placeholder is removed. A pasted image is named `pasted-YYYYMMDD-HHMMSS.png`.

Names are cleaned to letters, digits, `-` and `_` with a single extension, 40 characters at most, and made unique on the page. On a page you have not saved yet, the banner "Save the page once to add attachments." appears with a **Save and upload** button.

The **Attach** panel also lets you rename or delete an attachment (deleting asks for confirmation), and offers **Ingest as derived page** to turn an uploaded document into a new page.

### Drafts and recovery

While you are signed in, the editor saves a draft in your browser 0.8 seconds after you stop typing. If you close the tab or lose the connection, reopening the page shows "You have unsaved changes from ..." with **Restore** and **Discard**. A successful save clears the draft. Drafts live in your browser only, per account; **Resume editing** under **Me** in the sidebar lists them, each with a discard button. See [PersonalZone.md](PersonalZone.md).

### Edit conflicts and old versions

If someone saved the page after you opened it, saving shows **Version Conflict** with three choices: **Overwrite with my version**, **Discard my changes (load server version)** and **Copy my text to clipboard, then load server version**.

To go back to an older version, open **Change Notes** on the page, or the version comparison page, and click **Restore**. That opens the editor with the old text and the banner "Editing a copy of version N — saving creates version N+1". See [Reading.md](Reading.md#see-the-page-history-and-compare-versions).

### Other editor banners

- "This page uses legacy wiki syntax. Convert to Markdown?" offers a **Convert to Markdown** button.
- On a derived page the editor warns that the body is machine-generated and a reflow overwrites hand edits. Curate the frontmatter, tags and Knowledge Graph instead.

## Edit the page's metadata

The **Frontmatter** tab holds a form for the page's metadata: type, status, summary, tags, cluster, related pages and more. The **Form** and **Raw YAML** tabs inside it let you switch between fields and the raw YAML. Fields that matter most appear first, with the rest under **More fields**.

The form validates as you type (after a 400 ms pause). A strip above the form shows the number of errors and warnings; click one to jump to the field. Each problem also appears under its field, and some carry a **Use "..."** button that applies the fix. **Save is disabled while any error exists**, with the tooltip "Fix the highlighted errors before saving"; warnings never block a save. Every field, rule and error code is in [Frontmatter.md](Frontmatter.md).

The **Knowledge** tab is page-scoped Knowledge Graph curation. It appears for every page but only does something on pages the Knowledge Graph includes.

## Math validation messages

Math is checked when you save, not as you type. If a problem is found, the **Math validation** panel appears above the toolbar with a severity badge, a message, an excerpt with a caret and a **Jump** button that moves the cursor to the spot. Editing the text clears the panel.

| Message | Severity | Fix |
|---------|----------|-----|
| "Display math ($$…$$) is glued to surrounding text and will render as literal text. Put the $$ delimiters on their own lines." | Error, blocks the save | Put each `$$` on its own line. |
| "Unterminated display-math block: an opening $$ has no matching closing $$." | Error, blocks the save | Add the closing `$$`. |
| "Inline $…$ contains prose (...); a literal '$' (e.g. currency) is likely being parsed as math — escape it as \$." | Warning, the page still saves | Write `\$5` for a dollar amount. |
| "Empty display-math block ($$ $$)." | Warning, the page still saves | Fill or remove the block. |

See [MathematicalNotation.md](MathematicalNotation.md) for the syntax rules.

## Restrict who can see or edit a page

A page's access rules are plain text inside the page body, written as `[{ALLOW action principal,principal}]`:

```markdown
[{ALLOW view Admin}]
[{ALLOW edit Alice,Bob}]
```

The action is a page permission such as `view`, `edit`, `comment`, `upload`, `rename` or `delete`; the principals are user names, group names or roles, separated by commas. A directive inside a code span or fenced code block is documentation and is ignored. Once a page has an `ALLOW` rule, only the principals it names get that action. Wiki-wide default permissions are set by an administrator, not in the page.

## Mention people

@-mentions work in comments, not in the page editor. See [CommentsAndMentions.md](CommentsAndMentions.md).

## Source files

- `wikantik-frontend/src/components/PageEditor.jsx`, `CodeEditor.jsx`, `EditorToolbar.jsx`
- `wikantik-frontend/src/utils/editorCommands.js`, `slashComplete.js`, `wikiLinkComplete.js`, `mentionLink.js`
- `wikantik-frontend/src/components/editor/` — rail, status bar, unlinked-mentions panel
- `wikantik-frontend/src/components/NewArticleModal.jsx`, `AttachmentPanel.jsx`, `MathValidationSummary.jsx`
- `wikantik-frontend/src/hooks/useDraft.js`, `useEditorMode.js`, `useAttachmentUpload.js`
- `wikantik-rest/src/main/java/com/wikantik/rest/PageTemplatesResource.java`, `MentionScanResource.java`

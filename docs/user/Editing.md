# Creating and Editing Pages

This guide is for people who write and edit wiki pages in the web editor. It covers creating a page, the editor's toolbar and keyboard shortcuts, the Rich and Source modes, link completion, attachments, drafts, the frontmatter form, validation messages and page access rules. In every shortcut below, **Mod** means Ctrl on Windows and Linux and Cmd on a Mac.

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
2. The **Frontmatter** tab (see [Edit the page's metadata](#edit-the-pages-metadata)).
3. The formatting toolbar.
4. The Markdown source on the left and a rendered preview on the right, with a side rail (outline, backlinks, unlinked mentions) at the far right.
5. A status bar showing word count, reading time and the cursor line and column.

Type a short **Change note…** to describe the edit. It is stored with the version and shown in the page's history. If you leave it blank the note is "Created page" or "Updated page".

Click **Save** or press **Mod+S**. A successful save returns you to the page view with a "Saved" message. Warnings do not stop a save: you see "Saved with N advisory warning(s)" or "Saved with N math warning(s)". **Cancel** returns to the page view; if you have unsaved changes the wiki first asks whether to leave.

### Toolbar and keyboard shortcuts

The toolbar buttons and shortcuts all run entries from one command registry (`commands/registry.js`, `utils/editorCommands.js`), so they match the command palette. The toolbar works the same way in both editor modes: in Source it writes the same Markdown you would type.

| Toolbar control | Command | Shortcut |
|-----------------|---------|----------|
| **B** | Bold | Mod+B |
| **I** | Italic | Mod+I |
| Strikethrough | Strikethrough | Mod+Shift+X |
| Bulleted list, numbered list, task list | Make the line a list item of that kind | none |
| Quote | Block quote | none |
| `` ` `` | Inline code | none |
| Table | Insert table | none |
| link icon | Insert or edit a link | Mod+K |
| **Heading** menu | Heading 1 to 4, or Paragraph | none |
| **Insert** menu | The blocks listed below | none |
| **Rich** / **Source** | Switch editing mode | Mod+E |

A formatting button is lit when the cursor is in text of that kind, and pressing it again takes the formatting off. The **Heading** menu shows the level of the line the cursor is on; choose **Paragraph** to turn a heading back into ordinary text. The **Insert** menu holds the rest: code block, Mermaid diagram, PlantUML diagram, math block, the five callouts (note, tip, info, warning, danger), image, page embed, footnote, highlight, comment, horizontal rule and access rule. **Access rule** asks for a permission and the names allowed to use it, then writes the rule on its own line above the cursor. On a narrow screen, buttons that do not fit move into the **Insert** menu. With the toolbar focused, the Left and Right arrow keys move along it and Tab leaves it.

Other keys bound while you edit:

| Keys | Action |
|------|--------|
| Mod+S | Save the page |
| Mod+E | Switch between Rich and Source |
| Mod+Shift+H | Highlight the selection |
| Mod+/ | Comment the selection |
| Alt+T | Reach the table controls (Rich mode, cursor in a table) |
| Mod+Shift+V | Paste as plain text (see [Paste formatted text](#paste-formatted-text)) |
| Ctrl+Alt+[ | Fold all headings |
| Ctrl+Alt+] | Unfold all headings |
| Ctrl (Cmd) + hover over a link | Show a preview card for the link |
| Ctrl (Cmd) + click a link | Open it in a new tab |
| Esc | Close an open preview card, or close the outline rail on a narrow window |

Mod+K inserts or edits a link while the cursor is in the editor. Outside the editor it opens the page switcher; see [Search.md](Search.md#jump-to-a-page-or-run-a-command). Mod+O, Mod+P and Mod+Alt+N work everywhere, including in the editor.

Mod+E does nothing while a dialog is open. A page still written in the legacy wiki markup opens in Source only.

The command palette (Mod+P) also lists commands with no key: Heading 1, 2 and 3, five callouts, Insert table, Code block, Math block, Horizontal rule, Insert image, **Change code language / callout type**, **Show Markdown source beside the editor**, **Toggle preview** (hide or show the preview pane), **Toggle rail** and **Paste as plain text**.

### Insert blocks with the slash menu

Type `/` at the start of a line or after a space to open a menu of blocks. It offers the same blocks as the **Insert** menu, plus Heading 1 to 4, the bulleted, numbered and task lists, Quote, Table and Link. Keep typing to narrow it (for example `/tab`), press Up and Down to choose, and press Enter to insert. It does not open inside frontmatter, code, a URL or math.

### Rich and Source modes

The editor has two modes. **Rich** shows the page much as it will read and lets you build it from the toolbar, without typing Markdown. **Source** shows the Markdown itself. The page text is plain Markdown in both, and switching modes never changes it.

#### Switch modes

- Choose **Rich** or **Source** at the right-hand end of the toolbar, or press **Mod+E**. Nothing else switches the mode.
- Your choice is saved to your account when you are signed in, and otherwise in this browser. To change the default, open **Preferences**, go to **Editor** and set **Default editing mode**. New users start in Rich mode.
- A page written in the legacy wiki syntax opens in Source only. Use **Convert to Markdown** to get Rich mode.

#### What Rich mode shows

Rich mode keeps most markup out of sight, including on the line you are typing on.

- **Headings, lists, task boxes and quotes** show without their markers. Backspace at the start of such a line removes the marker and leaves the text.
- **Links, page embeds and images** show as a link or a picture. Click one to change it (see [Links, embeds and images](#links-embeds-and-images)).
- **Tables** are edited cell by cell (see [Tables](#tables)).
- **Code blocks, diagrams and math blocks** show without their fence lines. A code block has a small header with a language list.
- **Callouts** show their title, with a list to change the type.

Some markup still appears when the cursor comes to it:

- **Bold, italic, strikethrough, highlight and inline code** show their marks on the line the cursor is on, and hide them again when the cursor leaves.
- **A diagram or a math block** is drawn until the cursor moves into it; then you see and edit its source. Move the cursor out and it is drawn again.
- **A link you are typing** shows its source until the cursor leaves it.
- **Formulas inside a line, footnotes, comments, access rules, block ids and raw HTML** show their source on the cursor's line.

Two keys behave differently from plain text. Backspace just after a link, or Delete just before one, first selects the whole link; press the key again to remove it. When a code block or a table is the first or last thing on the page, the Up or Down arrow key adds an empty line outside it, so you can always get out.

#### Tables

In Rich mode a table is a grid. Use the table button, or **Insert table** in the `/` menu, to start one.

- Click a cell and type. What you type is saved as part of the page straight away, so in a shared session others see it as you write.
- **Tab** goes to the next cell and **Shift+Tab** to the previous one. **Enter** goes down to the same column of the next row. Tab or Enter at the end of the table adds a row.
- While the cursor is in a table, **+ row**, **+ col** and a **⋮** menu appear beside it. The menu adds a row above or a column to the left, moves the column left or right, sets its alignment, and deletes the row or column. Press **Alt+T** to reach these controls from the keyboard, and **Escape** to return to the cell.
- A cell holds one line of text. Bold, links and the rest work in a cell as they do in a paragraph. A vertical bar typed in a cell is kept as text. Spaces typed in front of a cell's text are not kept.
- With text selected across several cells, typing or deleting changes one cell only, and Cut removes nothing. To change several cells at once, switch to Source.

Some tables are shown as a table but are not editable as a grid; click one to edit its Markdown rows instead. These are tables whose rows do not all start and end with a vertical bar, tables whose rows have different numbers of cells, tables with more than one header row, and tables inside a list or a quote. Giving every row the same number of cells and a bar at each end makes the table a grid again:

```
| Step   | Owner |
| ------ | ----- |
| Deploy | Ops   |
```

#### Links, embeds and images

- Click a link, a page embed or an image in Rich mode to open a small card with its details: the page, heading and text of a wiki link; the address and text of a web link; the source and alt text of an image.
- Change a field and press **Enter**, or click away, to apply it. **Escape** closes the card and changes nothing. **Remove** takes the link off and keeps its text. **Open** follows the link.
- Select some text on one line and press **Mod+K** (or the link button) to make it a web link: the card opens for the address, and the page is not changed until you confirm it. With the cursor on a link, Mod+K opens that link's card. With nothing selected, it starts a wiki link and lists pages to pick from.
- Mod+K does nothing when the selection runs across table cells, or already holds a link or a web address. Select only the text you want linked.
- **Mod+S** while a card is open applies the card's change first and then saves. If the card cannot accept what you typed, it stays open and nothing is saved.

#### Code blocks and callouts

- A code block's header has a list of languages; pick one to set how the block is highlighted. A new code block from the toolbar starts empty, with the cursor inside it.
- A callout's title has a list of types (note, tip, info, warning, danger). Pick one to change the callout; edit the title text in place.
- Both lists change only that one word of the page.
- From the keyboard, put the cursor in the code block or callout, open the command palette (Mod+P) and run **Change code language / callout type**. The list takes the focus; press Enter or Space to open it.

#### The Markdown source pane

In Rich mode, the pane beside the editor can show the page's Markdown instead of the rendered preview. Choose **Source** at the top of the pane, or run **Show Markdown source beside the editor** from the command palette; choose **Preview** to go back.

- The pane is read-only. It follows the cursor, with the current line highlighted, so you can watch the Markdown your clicks produce. You can select and copy from it.
- It shows the body of the page. The metadata is edited on the **Frontmatter** tab and is not shown here.
- It is not a way to switch mode. To edit the Markdown, switch the editor to Source.

The rendered preview is a separate pane. It re-parses your text as you type and scrolls with the editor.

#### When the page is read-only

The editor is read-only when you may not edit the page, and for a moment when a shared draft is catching up with the server. While it is read-only, the toolbar, the `/` menu and the shortcuts change nothing, and **Copy block link** writes nothing for a block that has no id yet. Try again once you can type.

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

### Paste formatted text

Text copied from Google Docs, Word, Confluence, GitHub, a web page or another wiki page arrives as Markdown: headings, bold, italic, strikethrough, inline code, links, nested lists, task lists, tables, block quotes, code blocks (with their language when the source names one) and images become their Markdown forms. Copied math rendered by KaTeX, including math copied from this wiki, becomes `$…$` or `$$…$$`. A link to a page on this wiki becomes `[text](PageName)`. Page chrome such as copy buttons, heading permalinks, citation markers and edit links is left out, and a `$` in ordinary text is escaped as `\$` so it is not read as math.

The first converted paste in an editing session shows "Pasted as Markdown — Mod+Shift+V pastes plain text". Other cases:

- **Mod+Shift+V** pastes the clipboard's plain text with no conversion. **Paste as plain text** in the command palette does the same; if the browser does not allow the page to read the clipboard, it says so and suggests the shortcut.
- Text copied from a code editor or terminal, or plain paragraphs with no formatting, are pasted as they are.
- Inside a code block or inline code the paste is never converted.
- On a page still written in the legacy wiki markup, every paste is plain text.
- Paste a URL while text is selected to turn the selection into a link: `[selection](URL)`, or `[selection](PageName)` for a page on this wiki. With nothing selected, a URL is pasted as it is.
- Images embedded in the copied document itself (Google Docs and Word put them on the clipboard as data, not as a web address) cannot be pasted as links. A message gives the number left out; save the image and add it as an attachment.

One undo (Mod+Z) removes the whole paste.

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

### Editing together

When an administrator has turned collaborative editing on, opening the editor joins the page's shared draft: everyone
editing the page types into the same text, sees the others' names and cursors, and **Save** becomes **Publish**, which
writes the draft as one new version and credits everyone whose changes it includes. Unpublished changes stay in the
shared draft when you leave. See [CollaborativeEditing.md](CollaborativeEditing.md) for the full guide.

### Other editor banners

- "This page uses legacy wiki syntax. Convert to Markdown?" offers a **Convert to Markdown** button.
- On a derived page the editor warns that the body is machine-generated and a reflow overwrites hand edits. Curate the frontmatter and tags instead.

## Edit the page's metadata

The **Frontmatter** tab holds a form for the page's metadata: type, status, summary, tags, cluster, related pages and more. The **Form** and **Raw YAML** tabs inside it let you switch between fields and the raw YAML. Fields that matter most appear first, with the rest under **More fields**.

The form validates as you type (after a 400 ms pause). A strip above the form shows the number of errors and warnings; click one to jump to the field. Each problem also appears under its field, and some carry a **Use "..."** button that applies the fix. **Save is disabled while any error exists**, with the tooltip "Fix the highlighted errors before saving"; warnings never block a save. Every field, rule and error code is in [Frontmatter.md](Frontmatter.md).

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

The action is a page permission such as `view`, `edit`, `comment`, `upload`, `rename` or `delete`; the principals are login names, group names or roles, separated by commas. Prefer naming a group or role. Name a person by their login name; a full name or wiki name grants nobody. The directive lines are shown only to people who can edit the page (everyone else, and search, exports and agents, get the page without them), but anyone who can edit the page sees every login written in them. A directive inside a code span or fenced code block is documentation and is ignored. Once a page has an `ALLOW` rule, only the principals it names get that action. Wiki-wide default permissions are set by an administrator, not in the page.

## Mention people

@-mentions work in comments, not in the page editor. See [CommentsAndMentions.md](CommentsAndMentions.md).

## Source files

- `wikantik-frontend/src/components/PageEditor.jsx`, `CodeEditor.jsx`, `EditorToolbar.jsx`
- `wikantik-frontend/src/utils/editorCommands.js`, `slashComplete.js`, `wikiLinkComplete.js`, `mentionLink.js`
- `wikantik-frontend/src/utils/paste/` — paste-as-Markdown: `htmlToMarkdown.js` (clipboard HTML cleanup + Turndown rules), `pasteDecision.js`, `markdownPaste.js` (the CodeMirror handler)
- `wikantik-frontend/src/components/editor/` — rail, status bar, unlinked-mentions panel
- `wikantik-frontend/src/components/NewArticleModal.jsx`, `AttachmentPanel.jsx`, `MathValidationSummary.jsx`
- `wikantik-frontend/src/hooks/useDraft.js`, `useEditorMode.js`, `useAttachmentUpload.js`
- `wikantik-rest/src/main/java/com/wikantik/rest/PageTemplatesResource.java`, `MentionScanResource.java`

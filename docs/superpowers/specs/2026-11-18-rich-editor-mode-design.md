# Rich editor mode — design

Date: 2026-11-18. Status: implemented 2026-11-18, with inline marks fallen back to reveal-at-caret and the
differences listed under [As built](#as-built) at the end. The body below is the design as approved.

## Intent

The people who write on this wiki are product managers and engineers producing business requirements,
architecture notes, operational playbooks and runbooks, for human and agent readers. All of them know what
Markdown is. A good share are fluent in it and prefer it. The rest should not need a detailed knowledge of
it to write a well-formed page.

Today's editor assumes fluency in too many places: a table is edited as raw pipe rows, several constructs
can only be typed, and changing an existing link or callout means editing its markup.

The owner's rule for this work: **nobody should have to type Markdown; where it is cheap, they should not
have to see it either; switching between the rich view and the source is an explicit action.**

## Goals

1. Every construct an author in this audience uses can be inserted from the toolbar, the `/` menu or a
   shortcut, and changed afterwards without editing markup.
2. A **Rich** mode in which markup stays hidden under the cursor wherever that is cheap (the table below
   says where).
3. **Source** mode is unchanged for people who prefer Markdown.
4. Typing Markdown by hand keeps working in Rich mode: the document is text, and `**bold**` typed there is
   bold.

## Non-goals

- A new editor library, or a document model other than the Markdown text. (Owner decision: extend the
  existing CodeMirror implementation.)
- Any change to the stored page format, the reader, the dialect or the shared fixtures.
- Two live editors side by side (see Deferred).
- The work-item body editor (`WorkBodyEditor`) and the frontmatter form: untouched.
- Legacy wiki-syntax pages: they stay Source-only, as today.

## Anti-goals

- Rich mode changes a page's text when the author did nothing. Opening a page in Rich and saving must
  produce a byte-identical page.
- An action rewrites text the author did not touch (re-aligning a whole table to edit one cell,
  normalising list markers, changing line endings). Besides being wrong in itself, it turns one person's
  small edit into a conflict for everyone else in a shared session.
- A third mode, or a preference that makes Rich behave like two modes. Every mode is multiplied through
  the shared-editing and mode-compatibility suites.

## Constraints

- **Shared editing (MultiEdit) is live in production** and exchanges text changes to the Markdown source
  (`docs/MultiEdit.MD`). Every Rich action must end as an ordinary text change dispatched through the same
  write path `CodeEditor` uses today, so the collab protocol, undo history and the server are unaffected.
- **The reader is the arbiter** of what the syntax means. Rich mode draws from the same Lezer tree the
  live preview uses, which the shared fixtures hold to the reader. Nothing here adds a second reading of
  the syntax.
- No new runtime dependencies in `wikantik-frontend`.
- Performance guards stay: the `linearCost` tests, the preview-cost test, and incremental rebuilds in
  `utils/livePreview/plugin.js` (a caret move must not walk the document).
- Prod pages are CRLF; a row or line rewritten by an action keeps the line ending it had.

## Approaches considered

1. **Two modes: Rich and Source (chosen).** Today's live preview becomes Rich. Markup is hidden under the
   cursor where cheap and revealed at the cursor only for the expensive constructs. People who liked
   reveal-on-cursor for bold and links use the toolbar or switch to Source. Mode count stays at two.
2. **Three modes: Rich, Live, Source.** Nobody loses today's behaviour, at the price of a third mode in
   every test matrix and two near-identical modes that drift. Rejected.
3. **Two modes with "reveal at cursor" as a setting inside Rich.** Option 2's cost with the third mode
   hidden in a preference. Rejected.

Also considered and rejected before these: a separate rich-text editor (ProseMirror or similar) that
serialises to Markdown. It cannot keep untouched text byte-identical without great effort, it needs its
own collab story, and the owner ruled out a new library.

The owner chose approach 1.

## The design

### Modes

- Two modes, **Rich** and **Source**, switched only by an explicit action: the toolbar toggle or `Mod-E`.
  Nothing switches the mode as a side effect of clicking somewhere.
- The stored preference keeps its current values (`live` | `source`, browser storage and the account
  preference); `live` now means Rich. No migration. The user-visible label changes from "Live preview" to
  "Rich" everywhere a user reads it (toolbar, command palette, settings text); stored values, command ids
  and test names that say `live` stay. *(Author's call.)*
- Default stays `live`, so Rich is what a new user gets.

### What Rich hides, and what it still reveals

| Construct | In Rich mode | Changed by |
|---|---|---|
| Heading, bullet, numbered, task and quote markers | Always hidden (drawn as today's widgets) | Toolbar or `/`; Backspace at the start of the line removes the marker |
| Top-level tables | Always a grid, edited in cells | The grid (below) |
| Links, wikilinks, embeds, images | Always a chip or the drawn image | A card opened by clicking it (below) |
| Fenced code, diagrams (Mermaid, PlantUML), math blocks | Fence lines hidden; a small header shows the language or kind | Header picker; the body is edited as text in place |
| Callouts | Marker line drawn as the callout title, as today, also when the cursor is on it | Type picker on the title; the title text is edited in place |
| Bold, italic, strikethrough, highlight, inline code | Marks always hidden | Toolbar and shortcuts, which show and toggle the state at the cursor |
| Inline math, footnotes, `%%` comments, access rules, raw HTML, backslash escapes, block ids, reference-style links, tables inside a list or quote | Revealed at the cursor, as today | Typing; an insert command exists for each that an author would add on purpose |

The last row is the "expensive" set: rare for this audience, and each would need an editor of its own.
A construct can move up out of that row later without redesign.

Today the reveal is decided per active line (inline) and per active block (`activeLinesOf`,
`activeBlockKey`). Rich mode replaces "the cursor's line reveals everything on it" with "the cursor reveals
only the expensive constructs it touches".

### Inline marks: the one real risk

Hiding `**` under the cursor needs rules a word processor user would expect:

- The hidden marks are skipped by the cursor (atomic), never landed inside.
- A caret at the end of a bold run types bold; at the start it types plain. The toolbar button shows which.
  Pressing the toggle with no selection at the end of a run steps out of it.
- A selection edit that would orphan one mark of a pair (deleting half a bold run including its closing
  `**`) removes or re-places the partner so the text stays balanced.
- Copy yields the Markdown text, as today.

These are the author's proposed rules; the builder may refine them. **Gate:** build this construct last,
behind the same code path as the reveal behaviour, and start with a short throwaway spike. If the edge
behaviour cannot be made to feel right, inline marks stay in the reveal-at-cursor row and everything else
ships. That fallback must cost nothing but deleting the inline-mark work.

### The table grid

```
 | Step      | Owner  | Rollback        |   click a cell: type in it
 | Deploy█   | Ops    | release script  |   Tab / Shift-Tab: next / previous cell
 | Verify    | QA     | n/a             |   Enter: same column, next row
                                            [+ row] [+ col] [⋮ align · move · delete]
```

- Applies to top-level GFM tables (what `TableWidget` draws today). Tables inside a list or quote stay as
  they are.
- A table gets the editable grid only when the grid can write every cell back to exactly the source span it
  read it from: cells are taken from the syntax tree's cell nodes (so an escaped pipe or a pipe in a code
  span is part of its cell), and every row has the delimiter row's column count. Any other table (ragged
  rows, anything the tree does not read as a table) keeps today's behaviour: drawn, and a click puts the
  caret on the source row. The corpus no-op test covers both kinds. *(Author's call.)*
- **Typing in a cell reaches the document as it is typed**, as one ordinary text change per keystroke, not
  on leaving the cell. Holding text back until blur would hide it from the others in a shared session and
  leave a remote change to that row to be merged by hand. This is a requirement on how cell editing is
  hosted, and the collab harness must be able to drive it; a host that cannot meet it is the wrong host.
  *(Author's call; it makes the grid dearer than a commit-on-blur form would be.)*
- A cell that is not being edited shows its content drawn (chips, bold) as a paragraph would. The cell
  being edited shows what a paragraph line under the cursor shows in Rich at that point in the work: inline
  marks are visible there until step 6 lands, hidden after.
- A cell edit rewrites **only that cell's text** in its source row. Other rows are not re-padded or
  re-aligned.
- Row operations insert or delete one line. Column operations (add, delete, move, set alignment) touch
  every row of the table in **one** transaction, so they undo as one step.
- A `|` typed in a cell is stored escaped (`\|`). A cell holds no line break; Enter moves down. Inline
  formatting in a cell works as it does in a paragraph. *(Author's calls. The builder checks the reader's
  handling of each before relying on it.)*
- Tab in the last cell adds a row.
- In a shared session, two people editing different cells of the same table must converge with both edits
  kept; a remote change to the table while a cell is being edited must not lose the local typing or the
  caret's cell.
- Cost: a 500-row table must stay within the existing preview-cost budget; editing one cell must not
  rebuild the whole grid's DOM.

### Cards for existing things

Clicking a link, wikilink, embed or image in Rich mode opens a small card anchored to it:

```
 See the plan  ┌ Link ──────────────────┐
               │ Page: RollbackPlan  ▾  │   page picker = the existing link completion
               │ Text: the plan         │
               └──── Open · Remove ─────┘
```

- Wikilink: page (with the existing completion), optional heading, optional display text.
- External link: URL and text. Image: source and alt text; a size field only for the embed form that
  already carries one (`ref.size` in `ranges.js`).
- "Remove" unwraps the link and keeps its text.
- The card writes one text change on commit (Enter or leaving the card), not one per keystroke. Escape
  discards.
- `Mod-K` on a selection or inside a link opens the same card.

Code block and diagram headers and the callout title use the same pattern for their one choice (language,
diagram kind, callout type).

### Insert surface

One command registry (`utils/editorCommands.js`) already feeds the toolbar, the `/` menu and the command
palette. Add the missing commands there so all three get them:

numbered list, task list, quote, strikethrough, heading levels 1–4, Mermaid diagram, PlantUML diagram,
footnote, page embed, access rule (a small form: permission and who), and the already-registered
highlight and comment exposed in the menus.

Toolbar:

```
 [B] [I] [S] [H▾] [•] [1.] [☑] [❝] [</>] [▦] [🔗] [⊕ Insert ▾]        Rich ◉ | Source
```

- Buttons show the state at the cursor (bold lit inside bold; the heading menu shows the current level).
- Every button has a text label for assistive technology and a tooltip with its shortcut.
- Less common items live under **Insert**. On a narrow screen, buttons that do not fit move into it.
- The toolbar and the insert commands work in **both** modes; in Source they insert the same text.
- Legacy wiki-syntax pages: the new commands behave exactly as the existing toolbar commands do there
  today (the toolbar is shown and inserts Markdown). No new per-syntax gating in this work; the cards, the
  grid and the hidden marks are Rich-only and so never appear on a legacy page. *(Author's call.)*
- The forms for footnote and access rule write the syntax the reader fixtures already define; the builder
  reads it from them and adds nothing to the dialect.

### Source mirror pane

In Rich mode the preview pane's slot can show the page's Markdown source instead, read-only, with the
cursor's line highlighted and kept in view. It is a learning aid: you see the Markdown your clicks
produce. It is the existing pane with different content; there is still one editor. It is **not** a way
to switch mode: clicking in it does nothing but select text for copying. *(Author's reading of the
owner's "switching stays explicit".)*

The pane's state becomes one of hidden, Preview or Source, replacing the `previewOpen` boolean. Source is
offered only in Rich mode; if the editor is switched to Source mode while the pane shows Source, the pane
shows Preview until Rich is back. The existing "Toggle preview" command hides the pane or restores what it
last showed. The pane choice is remembered the way `previewOpen` is today, no more. *(Author's calls.)*

### Accessibility

Toolbar is a real toolbar (arrow-key navigation, as the role requires). Cards trap focus and return it to
the editor. The grid is keyboard-complete: every row and column operation is reachable without a mouse.

## Order of work

Each step ships on its own and leaves the editor better than before.

1. Insert surface and toolbar state (both modes).
2. Rich mode itself: block markers hidden under the cursor, the label change, fence and callout headers.
3. Cards for links, embeds and images.
4. Table grid.
5. Source mirror pane.
6. Inline marks hidden (spike first; may fall back).

## Testing

- **No-op invariant:** for every page in the corpus round-trip test, entering Rich, moving the caret
  through the document and leaving produces zero changes.
- **Minimal-change tests** per action: the resulting change touches only the expected characters.
- **Shared editing:** each new action through the existing collab harness (two clients, interleaved), with
  the table cases above named explicitly.
- **Mode compatibility:** the existing suite, extended to the new commands in both modes.
- **Cost:** `linearCost` guards on the grid and on hidden inline marks.
- **Browser (Selenide):** edit a table cell and add a column; retarget a link through its card; insert a
  diagram from the toolbar; toggle Rich/Source and save. Per project rule, content-changing features get
  both unit and integration coverage.
- The shared reader fixtures are not edited by this work.

## Left to the builder

- How the cursor-reveal rule is restructured in `ranges.js` / `plugin.js`.
- Whether the grid extends `TableWidget` or replaces it, and how cell editing is hosted (a nested editable
  element vs. mapped positions in the main editor), provided the constraints above hold.
- Card component structure and styling, within the shared `src/components/ui/` layer.
- Exact shortcuts for the new commands, avoiding the existing ones.

## Deferred

- **Two live editors side by side** (Rich and Source both editable). Explored and set aside as a future
  maybe: one pane must own shared editing and undo, every editor-attached feature needs a rule for two
  views, and it adds another layout to the test matrix. Revisit after Rich has been used.
- Moving constructs out of the reveal-at-cursor row (inline math first, if asked for).
- The same toolbar on the work-item body editor.

## As built

Recorded 2026-11-18. Steps 1 to 5 of "Order of work" shipped. Step 6 did not, by the gate in "Inline marks".

### Fell back: inline marks

Bold, italic, strikethrough, highlight and inline code stay in the reveal-at-cursor row: their marks show on
the caret's line and are hidden elsewhere. The toolbar buttons still show and toggle the state at the caret.

The spike (headless Chrome and Firefox, real keys, 12 scenarios) found the design's rules alone unusable: the
right arrow stuck in front of a bold run, Delete in front of a run removed the bold, and a space typed at the
end of a run made the asterisks appear. Nine further mechanisms brought 7 of 12 scenarios to work; 3 stayed
surprising (bold toggled on with nothing selected, Markdown typed by hand, IME input) and 2 broken (un-bolding
part of bold-italic text, a backtick typed inside inline code). The cause is in Markdown itself: a run stops
being a run when a space sits just inside a mark or the run is emptied, the hidden marks then appear as text
nobody typed, and each such path needs its own repair; the repairs rewrite what was just typed, which cannot
be done during IME composition.

Not built or measured, noted by the spike as a cheaper idea: reveal only the marks of the run the caret is
in, not every mark on the caret's line. It needs none of the hidden-mark rules.

### Differs from the design

Links and cards:

- A caret strictly inside a link shows the link's source (a link being typed as `[[…]]` has to be seen), and
  so does a selection that ends inside one (a find match in a page name). The table's "always a chip" holds
  for a caret beside the link or elsewhere on its line.
- `Mod-K` on a selection writes nothing until the card is committed. The design had it wrap the selection
  first. It does nothing for a selection that crosses a table cell boundary or holds a whole link, an
  autolink or a bare URL.
- Backspace right after a link chip (Delete right before one) selects the chip; a second press deletes it.
- `Ctrl/Cmd-S` with the focus in the card writes the card's edit first; a value the card refuses keeps it
  open, and nothing is saved.

Code blocks:

- A code block at the very start or end of a page is left with ArrowUp or ArrowDown, which insert an empty
  line outside it. A grid table at the start or end of a page is left the same way; there the key inserts a
  blank line and an empty line beyond it, since a single line next to the table would be read as a row.
- The Code block command in Rich puts the caret in the block's body and writes no placeholder language.
- The language and callout-type pickers stay native dropdowns in this release; a custom menu is a
  follow-up. The known residuals all fail safe (the pick is put back and nothing is written): touch devices
  that send no `mousedown`, an arrow key followed by a click in an open list, and Firefox on a Mac, which
  is unverified.
- The keyboard reaches a picker through the palette command "Change code language / callout type", offered
  while the caret is in a fenced block or a callout. It has no shortcut of its own.
- The no-space callout title form `[!note]Title` is pinned in the frontend tests only; a row in the shared
  server fixture is a follow-up.

Tables:

- Besides ragged tables and tables in a list or quote, a table without both its leading and trailing pipes
  on every row, and a table with several header rows, keep the old drawing and are not editable as a grid.
- With a selection across cells, Delete, Backspace and typing act on one cell; Cut across cells removes
  nothing.
- Search-and-replace passes through unchanged (a match may span a pipe).
- A `|` typed through an IME is stored unescaped; the table falls back to the old drawing until the pipe is
  repaired.
- Spaces and tabs typed or pasted in front of the text of a cell write nothing (they would be stored in the
  hidden padding and shown nowhere). A no-break or ideographic space is text and is kept.
- Block commands run with the caret in a grid cell (insert table, heading, list prefix) are written as
  dispatched and break the table, as they do in Source.

Keys:

- The guards on Backspace and Delete (beside a hidden fence line, a link chip, a line marker) also cover
  Shift-Backspace, Shift-Delete and the Mac's Ctrl-H, Ctrl-D, Ctrl-K and Ctrl-Alt-H.
- A delete that arrives without a key event bypasses them: a mobile or virtual keyboard. A transaction
  filter on `delete.*` is the follow-up.

Read-only documents and programmatic writes:

- A read-only editor (a frozen or out-of-sync shared draft, no edit permission) takes no format or insert
  command from the toolbar, the palette, the slash menu or a shortcut.
- A write the read-only editor refuses is reported, never taken as done: Convert then leaves the markup as
  it is, publishes nothing and says so; a queued attachment rename stays queued until the editor takes
  writes; Copy block link copies nothing for a block that would need an id written.
- A write that is exact by construction (the link card, a server-decided block id, every `applyChanges`
  call) carries the `asDispatched` annotation, and the grid's cell rules pass it through whatever its user
  event and wherever the selection is.
- The Footnote command writes two insertions, the marker and the definition, in one transaction.

Shared sessions:

- The caret is re-seated by the Rich caret rules after every collaborator change and after undo and redo,
  except during IME composition.

Source pane and insert surface:

- The source pane shows the page body: a leading frontmatter block is omitted. It has no syntax colouring
  beyond accented markers.
- Access-rule insertion is a small form: a permission and the names it is allowed to.

---
cluster: wikantik-development
canonical_id: 01KQ0P44Q1A992BXEZWGVTFYH4
---
# Edit Page Help

A short reminder of what the page editor offers. In shortcuts, **Mod** means Ctrl on Windows and Linux and Cmd on a Mac. For the full guide, see [Editing](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Editing.md).

## Start and save

* Choose **Edit** on a page to open the editor. You only see it if you have permission to edit.
* To make a page, use **+ New Article** in the sidebar (shown when you are signed in), or link to a page that does not exist yet.
* Add a **Change note** to say what you did, then press **Mod+S** or choose **Save**. **Cancel** leaves without saving.
* The editor keeps a draft in your browser while you type, and offers to restore it if you come back.

## Toolbar and keys

| Action | Key |
|--------|-----|
| Bold | Mod+B |
| Italic | Mod+I |
| Insert link | Mod+K |
| Toggle live preview | Mod+E |
| Save | Mod+S |

The toolbar also has heading, list, inline code, code block and table buttons. Type `/` at the start of a line for a menu of blocks such as tables, callouts and math.

## Writing

* The editor takes Markdown. See [TextFormattingRules](TextFormattingRules) for the syntax.
* Type `[[` to search for a page to link, and `[[Page#` to link to one of its headings.
* Paste or drag an image into the editor to attach it, or use **Attach**.
* The side rail lists the page outline, backlinks and **Unlinked mentions**, with one-click linking.

## Diagrams

A fenced code block whose language is `mermaid` renders as a diagram when the page is viewed. A `plantuml` block works the same way and suits deep class and activity diagrams; see *Class and activity diagrams with PlantUML* below. This Mermaid diagram is live:

```mermaid
flowchart LR
  Write --> Preview --> Save
```

To show diagram syntax as text instead of drawing it, add the word `example` after the language. The block below is written that way, so it appears as code:

```mermaid example
flowchart LR
  Draft --> Review
  click Review "/wiki/EditPageHelp"
```

* To make a node open a wiki page, add a line `click NodeId "/wiki/PageName"`. These links count as real page links, so they show up in backlinks, the Page Graph and broken-link reports.
* If a diagram has a syntax error, or is refused, the page shows its source together with the reason instead of a picture.
* The wiki refuses diagrams that contain `%%{ ... }%%` directives, a `---` front matter block, `click` lines that call a function, links that are not `http(s)` or relative, or `javascript:` anywhere. Tooltips in `click` lines cannot contain a colon.
* Renaming a page does not yet update `click` links to it in diagrams, so check those after a rename.

### Class and activity diagrams with PlantUML

Use a `plantuml` fence for class diagrams with deep structure, and for activity diagrams that need fork/join bars or swimlanes. Use Mermaid for everything else. This activity diagram is live:

```plantuml
@startuml
start
:Receive request;
fork
  :Check permissions;
fork again
  :Load page;
end fork
:Render response;
stop
@enduml
```

So is this class hierarchy, with an interface, an abstract class, a generic type and composition:

```plantuml
@startuml
interface PageProvider
abstract class AbstractProvider
class FileProvider
class Cache<T>
AbstractProvider ..|> PageProvider
FileProvider --|> AbstractProvider
FileProvider *-- Cache
@enduml
```

The `example` word works here too, so you can show the source as text:

```plantuml example
@startuml
class Page [[/wiki/EditPageHelp]]
@enduml
```

* To link a shape to a wiki page, add `[[/wiki/PageName]]` after it, as above. These links count as real page links, so they appear in backlinks.
* `@startuml` diagrams are accepted, plus `@startsalt` wireframes and `@startnwdiag` network diagrams (which need their own start line). `!include` is refused except for `<C4/...>` libraries, images and `{{ ... }}` embedded sub-diagrams are refused, and a diagram can be at most 64 KB.
* A diagram that fails shows its source together with the reason, as Mermaid does.

## Metadata

The **Frontmatter** tab edits the page's metadata (type, tags, cluster, summary and more) and checks it as you type. Save is disabled while an error is showing. See [Frontmatter](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Frontmatter.md).

## Conflicts

If someone saved the page after you opened it, you can overwrite their version, discard yours, or copy your text and reload.

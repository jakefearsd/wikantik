# Collaborative Editing

When collaborative editing is turned on for this wiki, several people can edit the same page at the same time. Everyone types into one shared draft, sees the others' cursors, and publishes the draft as a new page version when it is ready. This page explains how it works. For the editor itself, see [Editing.md](Editing.md).

Collaborative editing is off unless an administrator has turned it on. When it is off, the editor works as it always has: you edit alone and **Save** writes a new version.

## Join a page

* Choose **Edit** on a page. If you are signed in and allowed to edit it, you join the page's shared draft. You do not have to invite anyone or start a session.
* The bar above the editor shows a status: **Live** when you are connected, **Connecting** or **Reconnecting** while the connection is set up again, **Resync needed**, **Page deleted** or **Sign in again**.
* If your sign-in expires, your edits stay in the tab and are sent once you sign in again.

## See who else is editing

* The names of the other editors appear in the bar, each with a colour. Their cursors and selections show in the text in the same colour.
* Changes to tags, related pages, aliases and other frontmatter fields are shared too. Two people can add different tags at once and both are kept. For a single-value field such as the summary, the later change wins, and the form shows who changed it.

## The shared draft

Your edits go to the shared draft as you type. They are not part of the page until someone publishes.

* When a draft has changes nobody has published, a banner says so: "Unpublished changes by Ana and Raj — last edit 2h ago". Your own name reads "you".
* From the banner you can **Publish**, **View changes**, **Discard**, or **Dismiss** the banner.
* When you close the editor, your changes stay in the shared draft until someone publishes or discards them. You can come back later and carry on.

## Publish

Choose **Publish** in the editor, or in the banner. The publish dialog shows:

* what changed in the text since the last published version, or "No text changes";
* any frontmatter fields that changed;
* the people whose changes are included;
* a **Change note** box.

Any editor can publish. Publishing writes one new page version for everyone. If someone else is publishing at the same moment, you are asked to try again.

## Contributors in the page history

* The page history shows who published each version and who else contributed to it, for example "v8 — Ana +2". The change notes list the other contributors by name.
* Names are display names. If someone changes their display name later, the history shows the new one.

## Discard

**Discard** resets the shared draft to the published page for everyone in it. The editor asks you to confirm and says how many people are editing. Unpublished changes are lost.

## Offline drafts

When you edit alone, the editor keeps a copy in your browser. If you have one of those copies when you join a shared draft, the editor offers it once:

* If nobody else is editing, you can **Restore into the shared draft**. This brings back its text; the frontmatter stays as it is.
* If others are editing, it is not applied. You can **Copy my offline text** and paste in what you need.
* Either way you can **Discard it**. The shared draft does not change.

## Changes from outside the editor

A page can change while you edit it: an agent saves it, someone renames a cluster, or a link is rewritten. These changes go into the shared draft as they happen, and a notice says who made them. You do not get a conflict and you do not lose your edits.

## Renames and deletes

* If the page is renamed, the editor follows it to the new name and you keep editing.
* If the page is deleted, the editor becomes read-only and says "This page was deleted — copy your text or recreate the page". If you are allowed to create pages, **Recreate page** publishes the draft as the page again. If the deleted page was restricted, only an administrator can publish its draft.

## When the editor turns read-only

* **You lose edit permission.** The editor becomes read-only. Any edits that had not reached the shared draft are shown so you can copy them.
* **An administrator removes the draft.** The editor says "An administrator removed this shared draft, including its unpublished changes" and becomes read-only. Edits that had not reached the draft are shown so you can copy them.
* **Your editor loses its place.** After a long disconnection the editor may say "Your editor lost its place in the shared draft". It lists any edits of yours that did not arrive. Copy them, choose **Continue editing**, and paste them back.

## Convert to Markdown

A page in the old wiki syntax shows **Convert and publish**. Converting changes the page for everyone in the draft, so the editor converts the text and publishes it straight away, after asking you to confirm. If the publish fails, the converted text stays in the draft and a **Publish as Markdown** button lets you try again.

## Size limit

A shared draft cannot be published while it is over the page size limit. A banner shows the size and the limit; shorten the page to publish it.

## For administrators

* Collaborative editing is controlled by `wikantik.collab.enabled` (default off). Turning it off refuses new joins; editors
  already in a draft can still publish or discard it, and pages edit as before.
* Unpublished drafts are kept until someone publishes or discards them. `wikantik.collab.draft.retentionDays` (default
  `0`, keep forever) deletes drafts nobody has edited for that many days; a draft is never published automatically.
* Administrators can list the stored drafts with `GET /admin/collab/drafts` and remove one with
  `DELETE /admin/collab/drafts/{id}`. Removal is audited.
* Joins, publishes, discards, purges and evictions are written to the audit log (`/admin/audit`), and the collaboration
  meters are on `/metrics`. See `docs/admin/WikantikOperations.md` and `docs/MultiEdit.MD` section 15.

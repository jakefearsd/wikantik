# Work: find and saved filters

Reference detail moved word for word from `wikantik-work/CLAUDE.md`. The invariants are in that file; where a class Javadoc states a rule, the Javadoc is the authority. Read this before changing the area.

- **Facade.** `find()` is on `WorkFeatures` (`WorkService.features().find()`, `WorkFindService`), not on `WorkService`, which is at the PMD method limit. The stores sit on `store.notifyStores()` (`finder()`, `filters()`); that name now covers more than notifications.
- **Visibility.** The projects a find may search come from `WorkStanding` (`visibleProjects`) and are bound into the one statement as `project_id = ANY(?)`. A project the caller names but cannot see gives no rows, never an error.
- **Cursor.** `(sort value, id)`, bound to its sort. It is validated, not signed: a bad or foreign cursor is a 400 field `cursor`. It carries no visibility, so it never widens what the next request may see.
- **`me`** is resolved when the query runs (an agent's `me` is its claim holder and own items; agents have no watching).
- **`FindParams` is the only parser of the URL form**, and saved filters store that same form.
- **A shared filter searches its own project only** and is always run for the person who runs it. Saved-filter writes write no `work_event` (a preference table whose rows may be deleted).
- **`GET /api/work/items` has two modes, chosen by `project`:** with it, one project's list; without it, find. `FIND` is a reserved project key.

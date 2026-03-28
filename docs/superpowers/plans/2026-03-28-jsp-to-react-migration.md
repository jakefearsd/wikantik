# JSP to React Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 8 features in the React SPA to achieve full UI parity with the JSP pages, then catalog JSP dead code for removal.

**Architecture:** Features 1-3 and 8 are React-only (no backend changes). Features 4-7 require new REST endpoints in `wikantik-rest` + React components in `wikantik-frontend`. Each task produces a deployable, testable increment. Feature 9 is a documentation task.

**Tech Stack:** Java 21, React 18, React Router v6, Vite, existing REST/auth framework, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-03-28-jsp-to-react-migration-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `wikantik-frontend/src/components/PageView.jsx` | MODIFY — add Delete button, Rename button, CommentsPanel |
| `wikantik-frontend/src/components/PageEditor.jsx` | MODIFY — add conflict resolution modal |
| `wikantik-frontend/src/components/DiffViewer.jsx` | NEW — version comparison page |
| `wikantik-frontend/src/components/CommentsPanel.jsx` | NEW — comment list + add comment form |
| `wikantik-frontend/src/components/UserPreferencesPage.jsx` | NEW — self-service profile editing |
| `wikantik-frontend/src/components/ResetPasswordPage.jsx` | NEW — password recovery form |
| `wikantik-frontend/src/components/ChangeNotesPanel.jsx` | MODIFY — add "Compare" button linking to DiffViewer |
| `wikantik-frontend/src/components/LoginForm.jsx` | MODIFY — add "Forgot password?" link |
| `wikantik-frontend/src/components/UserBadge.jsx` | MODIFY — add "Preferences" link |
| `wikantik-frontend/src/main.jsx` | MODIFY — add routes for diff, preferences, reset-password |
| `wikantik-frontend/src/api/client.js` | MODIFY — add renamePage, getComments, addComment, getProfile, updateProfile, resetPassword |
| `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java` | MODIFY — add rename endpoint via service() routing |
| `wikantik-rest/src/main/java/com/wikantik/rest/CommentResource.java` | NEW — GET/POST comments |
| `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` | MODIFY — add profile and reset-password endpoints |
| `wikantik-war/src/main/webapp/WEB-INF/web.xml` | MODIFY — register CommentResource servlet |
| `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java` | MODIFY — add rename tests |
| `wikantik-rest/src/test/java/com/wikantik/rest/CommentResourceTest.java` | NEW — comment endpoint tests |
| `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java` | MODIFY — add profile and reset tests |

---

### Task 1: Page Delete (React only)

**Files:**
- Modify: `wikantik-frontend/src/components/PageView.jsx`
- Modify: `wikantik-frontend/src/api/client.js`

Add a delete button with confirmation modal to PageView, gated by `page.permissions.delete`.

- [ ] **Step 1: Add `deletePage` to API client** (if not already there)

The `deletePage` method already exists in `client.js` (line 37-38). Verify it works.

- [ ] **Step 2: Add delete button and confirmation modal to PageView.jsx**

After the Edit button (line 24-28), add Rename and Delete buttons. For now just Delete:

```jsx
{page.permissions?.delete && (
  <button
    className="btn btn-ghost btn-danger btn-sm"
    style={{ flexShrink: 0 }}
    onClick={() => setConfirmDelete(true)}
  >
    Delete
  </button>
)}
```

Add state: `const [confirmDelete, setConfirmDelete] = useState(false);`
Add navigate: `const navigate = useNavigate();`

Add confirmation modal (same pattern as admin delete confirmations):
```jsx
{confirmDelete && (
  <div className="modal-overlay" onClick={() => setConfirmDelete(false)}>
    <div className="modal-content admin-modal" onClick={e => e.stopPropagation()}>
      <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
        Delete Page
      </h3>
      <p>Are you sure you want to delete <strong>{name}</strong>? This cannot be undone.</p>
      <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
        <button className="btn btn-ghost" onClick={() => setConfirmDelete(false)}>Cancel</button>
        <button className="btn btn-primary btn-danger" onClick={async () => {
          try {
            await api.deletePage(name);
            navigate('/wiki/Main');
          } catch (err) {
            setConfirmDelete(false);
            // Show error via existing error state if available
          }
        }}>Delete Page</button>
      </div>
    </div>
  </div>
)}
```

Add imports: `import { useParams, Link, useNavigate } from 'react-router-dom';`
Add import for admin CSS: `import '../styles/admin.css';`

- [ ] **Step 3: Build and verify**

```bash
cd wikantik-frontend && npm run build
```

- [ ] **Step 4: Deploy and manually test**

Build WAR, deploy to Tomcat, verify:
- Delete button appears only when logged in as admin (only admin has delete permission)
- Clicking shows confirmation modal
- Confirming deletes page and redirects to Main

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/PageView.jsx
git commit -m "Add page delete button with confirmation modal to React SPA"
```

---

### Task 2: Page Conflict Resolution (React only)

**Files:**
- Modify: `wikantik-frontend/src/components/PageEditor.jsx`

Detect 409 Conflict on save and show resolution options.

- [ ] **Step 1: Replace generic 409 error with conflict resolution UI**

In `PageEditor.jsx`, the save handler (line 46) already detects 409:
```javascript
if (err.status === 409) {
  setError('Version conflict — someone else edited this page...');
```

Replace this with a conflict state:
```javascript
const [conflict, setConflict] = useState(null);
```

In the catch block:
```javascript
if (err.status === 409) {
  // Fetch the current server version
  try {
    const current = await api.getPage(name);
    setConflict({
      myContent: content,
      serverContent: reconstructContent(current.metadata, current.content),
      serverVersion: current.version,
    });
  } catch {
    setError('Version conflict detected, but could not load the current version.');
  }
}
```

- [ ] **Step 2: Add conflict resolution modal**

Below the editor, add:
```jsx
{conflict && (
  <div className="modal-overlay">
    <div className="modal-content admin-modal" style={{ maxWidth: '700px' }}>
      <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
        Edit Conflict
      </h3>
      <p>Someone else edited this page while you were working. Choose how to proceed:</p>
      <div className="modal-actions" style={{ marginTop: 'var(--space-lg)', flexDirection: 'column', gap: 'var(--space-sm)' }}>
        <button className="btn btn-primary" onClick={async () => {
          // Force save — ignore version check
          setSaving(true);
          try {
            await api.savePage(name, { content: conflict.myContent, changeNote: changeNote || 'Updated page (overwrite)' });
            navigate(`/wiki/${name}`);
          } catch (e) { setError(e.message); } finally { setSaving(false); setConflict(null); }
        }}>Overwrite with my changes</button>
        <button className="btn btn-ghost" onClick={() => {
          setContent(conflict.serverContent);
          setOriginalVersion(conflict.serverVersion);
          setConflict(null);
        }}>Discard my changes (load server version)</button>
        <button className="btn btn-ghost" onClick={() => {
          navigator.clipboard.writeText(conflict.myContent);
          setContent(conflict.serverContent);
          setOriginalVersion(conflict.serverVersion);
          setConflict(null);
          setError('Your changes were copied to clipboard. The server version is now loaded.');
        }}>Copy my changes to clipboard, then load server version</button>
      </div>
    </div>
  </div>
)}
```

- [ ] **Step 3: Build, deploy, and test**

Test by opening two browser tabs, editing the same page, saving in one tab, then saving in the other.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/PageEditor.jsx
git commit -m "Add conflict resolution modal to page editor"
```

---

### Task 3: Diff / Version Comparison Viewer

**Files:**
- Create: `wikantik-frontend/src/components/DiffViewer.jsx`
- Modify: `wikantik-frontend/src/components/ChangeNotesPanel.jsx`
- Modify: `wikantik-frontend/src/main.jsx`

- [ ] **Step 1: Create DiffViewer.jsx**

New component at route `/app/diff/:name`. Fetches history, lets user select two versions, shows diff HTML.

```jsx
import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import '../styles/admin.css';

export default function DiffViewer() {
  const { name } = useParams();
  const [versions, setVersions] = useState([]);
  const [fromVer, setFromVer] = useState(null);
  const [toVer, setToVer] = useState(null);
  const [diffHtml, setDiffHtml] = useState(null);
  const [loading, setLoading] = useState(true);
  const [diffLoading, setDiffLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.getHistory(name).then(data => {
      const v = data.versions || [];
      setVersions(v);
      if (v.length >= 2) {
        setFromVer(v[v.length - 1].version);
        setToVer(v[0].version);
      }
    }).catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [name]);

  const loadDiff = async () => {
    if (fromVer == null || toVer == null) return;
    setDiffLoading(true);
    try {
      const data = await api.getDiff(name, fromVer, toVer);
      setDiffHtml(data.diffHtml || data.diff || '');
    } catch (err) { setError(err.message); }
    finally { setDiffLoading(false); }
  };

  useEffect(() => { if (fromVer != null && toVer != null) loadDiff(); }, [fromVer, toVer]);

  if (loading) return <div className="loading">Loading history…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-lg)' }}>
        <h2 style={{ fontFamily: 'var(--font-display)' }}>
          <Link to={`/wiki/${name}`} style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>{name}</Link> — Version Comparison
        </h2>
      </div>
      <div style={{ display: 'flex', gap: 'var(--space-md)', alignItems: 'center', marginBottom: 'var(--space-lg)' }}>
        <label>From: <select value={fromVer || ''} onChange={e => setFromVer(Number(e.target.value))}>
          {versions.map(v => <option key={v.version} value={v.version}>v{v.version} — {v.author || 'unknown'}</option>)}
        </select></label>
        <label>To: <select value={toVer || ''} onChange={e => setToVer(Number(e.target.value))}>
          {versions.map(v => <option key={v.version} value={v.version}>v{v.version} — {v.author || 'unknown'}</option>)}
        </select></label>
      </div>
      {diffLoading && <div className="loading">Loading diff…</div>}
      {diffHtml && (
        <div className="article-prose" style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius-md)', padding: 'var(--space-md)' }}
          dangerouslySetInnerHTML={{ __html: diffHtml }} />
      )}
      {!diffHtml && !diffLoading && <p style={{ color: 'var(--text-muted)' }}>Select two versions to compare.</p>}
    </div>
  );
}
```

- [ ] **Step 2: Add route in main.jsx**

```jsx
import DiffViewer from './components/DiffViewer';
// Add route:
<Route path="/diff/:name" element={<DiffViewer />} />
```

- [ ] **Step 3: Add "Compare" link in ChangeNotesPanel.jsx**

After the version list, add a link:
```jsx
<Link to={`/diff/${pageName}`} style={{ fontSize: '0.75rem', color: 'var(--accent)' }}>
  Compare versions
</Link>
```

- [ ] **Step 4: Build, deploy, verify**

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/DiffViewer.jsx \
       wikantik-frontend/src/components/ChangeNotesPanel.jsx \
       wikantik-frontend/src/main.jsx
git commit -m "Add diff/version comparison viewer to React SPA"
```

---

### Task 4: Page Rename (Backend + React)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java`
- Modify: `wikantik-frontend/src/api/client.js`
- Modify: `wikantik-frontend/src/components/PageView.jsx`

- [ ] **Step 1: Write failing test for rename endpoint**

Add to `PageResourceTest.java`:
```java
@Test
void testRenamePageMissingNewName() throws Exception {
    engine.saveText("RenameSource", "Content to rename.");
    final JsonObject body = new JsonObject();
    // No "newName" field
    final HttpServletRequest request = createRequest("RenameSource/rename");
    Mockito.doReturn(new BufferedReader(new StringReader(body.toString()))).when(request).getReader();
    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    final StringWriter sw = new StringWriter();
    Mockito.doReturn(new PrintWriter(sw)).when(response).getWriter();
    servlet.doPost(request, response);
    final JsonObject obj = gson.fromJson(sw.toString(), JsonObject.class);
    assertTrue(obj.get("error").getAsBoolean());
    assertEquals(400, obj.get("status").getAsInt());
}
```

- [ ] **Step 2: Implement rename in PageResource.java**

Add `doPost` method to `PageResource`:
```java
@Override
protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
        throws ServletException, IOException {
    final String pathParam = extractPathParam(request);
    if (pathParam != null && pathParam.endsWith("/rename")) {
        final String pageName = pathParam.substring(0, pathParam.length() - "/rename".length());
        handleRename(request, response, pageName);
        return;
    }
    sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint");
}

private void handleRename(final HttpServletRequest request, final HttpServletResponse response,
                           final String pageName) throws IOException {
    if (!checkPagePermission(request, response, pageName, "rename")) return;

    final JsonObject body;
    try (final BufferedReader reader = request.getReader()) {
        body = JsonParser.parseReader(reader).getAsJsonObject();
    } catch (final Exception e) {
        sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        return;
    }

    final String newName = body.has("newName") ? body.get("newName").getAsString() : null;
    if (newName == null || newName.isBlank()) {
        sendError(response, HttpServletResponse.SC_BAD_REQUEST, "newName is required");
        return;
    }

    final Engine engine = getEngine();
    final PageManager pm = engine.getManager(PageManager.class);
    if (pm.getPage(newName) != null) {
        sendError(response, HttpServletResponse.SC_CONFLICT, "Page already exists: " + newName);
        return;
    }

    try {
        final Context context = Wiki.context().create(engine, request, pm.getPage(pageName));
        final PageRenamer renamer = engine.getManager(PageRenamer.class);
        renamer.renamePage(context, pageName, newName, true);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("oldName", pageName);
        result.put("newName", newName);
        sendJson(response, result);
    } catch (final Exception e) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Rename failed");
    }
}
```

- [ ] **Step 3: Run tests**

```bash
mvn test -pl wikantik-rest -Dtest=PageResourceTest
```

- [ ] **Step 4: Add renamePage to API client**

```javascript
renamePage: (name, newName) =>
  request(`/api/pages/${encodeURIComponent(name)}/rename`, {
    method: 'POST',
    body: JSON.stringify({ newName }),
  }),
```

- [ ] **Step 5: Add rename button and modal to PageView.jsx**

```jsx
const [renameModal, setRenameModal] = useState(false);
const [newName, setNewName] = useState('');

// Button next to Edit:
{page.permissions?.rename && (
  <button className="btn btn-ghost btn-sm" onClick={() => { setNewName(name); setRenameModal(true); }}>
    Rename
  </button>
)}

// Modal:
{renameModal && (
  <div className="modal-overlay" onClick={() => setRenameModal(false)}>
    <div className="modal-content admin-modal" onClick={e => e.stopPropagation()}>
      <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>Rename Page</h3>
      <div className="form-field">
        <label>New name</label>
        <input type="text" value={newName} onChange={e => setNewName(e.target.value)} autoFocus />
      </div>
      <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
        <button className="btn btn-ghost" onClick={() => setRenameModal(false)}>Cancel</button>
        <button className="btn btn-primary" onClick={async () => {
          try {
            await api.renamePage(name, newName);
            navigate(`/wiki/${newName}`);
          } catch (err) { setError(err.message); setRenameModal(false); }
        }}>Rename</button>
      </div>
    </div>
  </div>
)}
```

- [ ] **Step 6: Build, deploy, test, commit**

```bash
git commit -m "Add page rename: REST endpoint + React modal"
```

---

### Task 5: Comments (Backend + React)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/CommentResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/CommentResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Create: `wikantik-frontend/src/components/CommentsPanel.jsx`
- Modify: `wikantik-frontend/src/components/PageView.jsx`
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Write tests for CommentResource**

Key tests: list comments on page with no comments (empty array), add comment, list again (contains new comment), add comment with empty text (400), add comment on nonexistent page (404).

- [ ] **Step 2: Implement CommentResource.java**

Extends `RestServletBase`. Mapped to `/api/comments/*`.
- `doGet` — extracts page name, loads page text, parses comment blocks (lines starting with `%%% comment` convention or a simpler JSON-based approach), returns as JSON array.
- `doPost` — adds a comment by appending to the page text with author/timestamp metadata. Uses `PageSaveHelper` to save.

Comment format in page text (appended at end):
```markdown

---
**Comment by {author} on {timestamp}:**
{text}
```

- [ ] **Step 3: Register in web.xml**

Add servlet and mapping for `/api/comments/*`.

- [ ] **Step 4: Add API client methods**

```javascript
getComments: (name) => request(`/api/comments/${encodeURIComponent(name)}`),
addComment: (name, text) =>
  request(`/api/comments/${encodeURIComponent(name)}`, {
    method: 'POST',
    body: JSON.stringify({ text }),
  }),
```

- [ ] **Step 5: Create CommentsPanel.jsx**

Shows comment list + add comment form. Lazy-loads comments on expand (same pattern as ChangeNotesPanel).

- [ ] **Step 6: Add CommentsPanel to PageView.jsx**

Below the article content, before tags.

- [ ] **Step 7: Build, deploy, test, commit**

```bash
git commit -m "Add comments system: REST endpoint + React panel"
```

---

### Task 6: User Preferences (Backend + React)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`
- Create: `wikantik-frontend/src/components/UserPreferencesPage.jsx`
- Modify: `wikantik-frontend/src/components/UserBadge.jsx`
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Write tests for profile endpoints**

Test GET `/api/auth/profile` returns user profile fields. Test PUT with password change (current password required). Test PUT with invalid current password returns 401.

- [ ] **Step 2: Implement profile endpoints in AuthResource**

Add handling for `/profile` subpath in `doGet` and `doPut`:
- GET: resolve session user, look up profile in UserDatabase, return JSON
- PUT: verify current password, validate new password with PasswordValidator, update profile

- [ ] **Step 3: Add API client methods**

```javascript
getProfile: () => request('/api/auth/profile'),
updateProfile: (data) =>
  request('/api/auth/profile', { method: 'PUT', body: JSON.stringify(data) }),
```

- [ ] **Step 4: Create UserPreferencesPage.jsx**

Form with Full Name, Email, Wiki Name (read-only), current password, new password, confirm password. Same form patterns as admin user edit.

- [ ] **Step 5: Add route and navigation links**

Route in `main.jsx`: `<Route path="/preferences" element={<UserPreferencesPage />} />`
Link in `UserBadge.jsx`: "Preferences" next to logout.

- [ ] **Step 6: Build, deploy, test, commit**

```bash
git commit -m "Add user preferences page: self-service profile and password change"
```

---

### Task 7: Lost Password Recovery (Backend + React)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`
- Create: `wikantik-frontend/src/components/ResetPasswordPage.jsx`
- Modify: `wikantik-frontend/src/components/LoginForm.jsx`
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Write tests for reset-password endpoint**

Test POST `/api/auth/reset-password` with valid email (returns generic success). Test with missing email (400). Test rate limiting (optional — may require timing).

- [ ] **Step 2: Implement reset-password in AuthResource**

Add handling for `/reset-password` subpath in `doPost`:
- Look up user by email in UserDatabase
- Generate random password via `TextUtil.generateRandomPassword()`
- Send via `MailUtil.sendMessage()`
- Save new password hash
- Return generic success (don't reveal if email exists)
- Rate limit via `TimedCounterList` (same pattern as login throttling)

- [ ] **Step 3: Add API client method**

```javascript
resetPassword: (email) =>
  request('/api/auth/reset-password', { method: 'POST', body: JSON.stringify({ email }) }),
```

- [ ] **Step 4: Create ResetPasswordPage.jsx**

Simple form: email input, submit button, success/error message.

- [ ] **Step 5: Add route and login form link**

Route: `<Route path="/reset-password" element={<ResetPasswordPage />} />`
Link in `LoginForm.jsx`: "Forgot your password?" below the login button.

- [ ] **Step 6: Build, deploy, test, commit**

```bash
git commit -m "Add lost password recovery: email-based reset with rate limiting"
```

---

### Task 8: JSP Dead Code Catalog

**Files:**
- Create: `docs/jsp-dead-code-catalog.md`

- [ ] **Step 1: Generate the catalog**

Create a document listing every file that becomes dead code after features 1-7 are implemented. Organize by category:

1. **JSP files** (68 files) — list each filename
2. **Custom tags** (70 classes in `com.wikantik.tags.*`) — list each class
3. **UI classes** (~25 in `com.wikantik.ui.*`) — list each, mark which to KEEP (WikiServletFilter, SitemapServlet, AtomFeedServlet, RecentArticlesServlet)
4. **Form classes** (10 in `com.wikantik.forms.*`) — list each
5. **Legacy JS** (`scripts/mootools.js`)
6. **web.xml changes** — list specific elements to remove

- [ ] **Step 2: Verify the catalog by checking each "keep" class**

For each class marked KEEP, verify it's actually used by non-JSP code (REST API, feed servlets, etc.).

- [ ] **Step 3: Commit**

```bash
git add docs/jsp-dead-code-catalog.md
git commit -m "Add JSP dead code catalog: 180 files identified for removal after React migration"
```

---

## Incremental Testing Strategy

After each task, run this verification sequence:

```bash
# 1. Frontend builds
cd wikantik-frontend && npm run build

# 2. Full Maven build (includes frontend)
cd .. && mvn clean install -Dmaven.test.skip -T 1C

# 3. Unit tests pass
mvn clean test -T 1C -DskipITs

# 4. Deploy and manual test
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
# Then test at http://localhost:8080/app/
```

Each task is independently deployable — after Task 1, you have delete; after Task 2, you have conflict resolution; etc. The JSP UI continues working throughout.

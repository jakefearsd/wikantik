# JSP Dead Code Catalog

After completing the React SPA migration (features 1-8), the following files become dead code and can be safely deleted. This catalog serves as the checklist for the JSP removal follow-up.

## Summary

| Category | Files | Estimated Size |
|----------|-------|---------------|
| JSP pages | 68 | ~968 KB |
| Custom tag classes | 69 | ~388 KB |
| UI classes (deletable) | 30 | ~280 KB |
| Form classes | 10 | ~72 KB |
| Legacy JavaScript | 1 | ~40 KB |
| Template assets | ~50 | ~450 KB |
| **Total** | **~228** | **~2.2 MB** |

## 1. JSP Pages (68 files)

### Root-level JSPs (25 files)
```
wikantik-war/src/main/webapp/Wiki.jsp
wikantik-war/src/main/webapp/Edit.jsp
wikantik-war/src/main/webapp/Login.jsp
wikantik-war/src/main/webapp/LoginForm.jsp
wikantik-war/src/main/webapp/Logout.jsp
wikantik-war/src/main/webapp/Search.jsp
wikantik-war/src/main/webapp/Preview.jsp
wikantik-war/src/main/webapp/Delete.jsp
wikantik-war/src/main/webapp/Diff.jsp
wikantik-war/src/main/webapp/Rename.jsp
wikantik-war/src/main/webapp/Comment.jsp
wikantik-war/src/main/webapp/Upload.jsp
wikantik-war/src/main/webapp/PageInfo.jsp
wikantik-war/src/main/webapp/Group.jsp
wikantik-war/src/main/webapp/EditGroup.jsp
wikantik-war/src/main/webapp/DeleteGroup.jsp
wikantik-war/src/main/webapp/NewGroup.jsp
wikantik-war/src/main/webapp/UserPreferences.jsp
wikantik-war/src/main/webapp/LostPassword.jsp
wikantik-war/src/main/webapp/Install.jsp
wikantik-war/src/main/webapp/Error.jsp
wikantik-war/src/main/webapp/Message.jsp
wikantik-war/src/main/webapp/CookieError.jsp
wikantik-war/src/main/webapp/SisterSites.jsp
wikantik-war/src/main/webapp/PageModified.jsp
```

### Template JSPs (43 files)
```
wikantik-war/src/main/webapp/templates/default/ (all files)
wikantik-war/src/main/webapp/templates/raw/ (all files)
wikantik-war/src/main/webapp/templates/reader/ (all files)
```

## 2. Custom Tag Classes (69 files)

**Package:** `com.wikantik.tags.*`

All 69 classes in `wikantik-main/src/main/java/com/wikantik/tags/` — used exclusively by JSP pages.

## 3. UI Classes

**Package:** `com.wikantik.ui.*`

### DELETE (30 classes)
```
AbstractCommand.java
AdminBean.java (interface)
AllCommands.java
CommandResolver.java (interface)
CoreBean.java
DefaultAdminBeanManager.java
DefaultCommandResolver.java
DefaultEditorManager.java
DefaultProgressManager.java
DefaultTemplateManager.java
Editor.java
EditorManager.java (interface)
FilterBean.java
GenericHTTPHandler.java
GroupCommand.java
InputValidator.java
Installer.java
ModuleBean.java
PageCommand.java
PlainEditorAdminBean.java
PluginBean.java
ProgressItem.java
ProgressManager.java (interface)
RedirectCommand.java
SearchManagerBean.java
SimpleAdminBean.java
TemplateManager.java (interface)
UserBean.java
WikiCommand.java
WikiFormAdminBean.java
WikiJSPFilter.java
AdminBeanManager.java (interface)
```

### KEEP (5 classes — used by REST API or feeds)
```
WikiServletFilter.java — REST API auth/session setup
WikiRequestWrapper.java — used by WikiServletFilter
SitemapServlet.java — sitemap.xml generation
```

**Note:** `AtomFeedServlet` and `RecentArticlesServlet` are in `com.wikantik.content`, not `com.wikantik.ui`.

## 4. Form Classes (10 files)

**Package:** `com.wikantik.forms.*`

```
FormClose.java
FormElement.java
FormHandler.java (interface)
FormInfo.java
FormInput.java
FormOpen.java
FormOutput.java
FormSelect.java
FormSet.java
FormTextarea.java
```

## 5. Legacy JavaScript

```
wikantik-war/src/main/webapp/scripts/mootools.js
```

## 6. Template Assets

```
wikantik-war/src/main/webapp/templates/default/images/ (all files)
wikantik-war/src/main/webapp/templates/default/admin/admin.css
```

## 7. web.xml Changes

Remove these elements:
- `WikiJSPFilter` filter definition and filter-mapping
- `WikiServlet` servlet definition and servlet-mapping for `/wiki/*` (keep WikiServletFilter — it's separate)
- `<jsp-config>` section (if present)
- Welcome file pointing to `Wiki.jsp`
- Container-managed auth section (currently commented out)
- `WikiAjaxDispatcherServlet` — verify if still needed by React SPA

## 8. TLD File

```
wikantik-war/src/main/webapp/WEB-INF/jspwiki.tld (if exists)
```

## 9. Test Files That Become Deletable

Tag-related test files (if any exist in `wikantik-main/src/test/java/com/wikantik/tags/`).

## Pre-Deletion Verification Checklist

Before deleting any files:
- [ ] All 8 React features are implemented and manually verified
- [ ] React SPA handles all user workflows end-to-end
- [ ] `mvn clean test -T 1C -DskipITs` passes
- [ ] `WikiServletFilter` is NOT deleted (REST API depends on it)
- [ ] `WikiRequestWrapper` is NOT deleted (used by WikiServletFilter)
- [ ] `SitemapServlet`, `AtomFeedServlet`, `RecentArticlesServlet` are NOT deleted
- [ ] After deletion, full build still succeeds
- [ ] After deletion, all tests still pass
- [ ] React SPA works correctly at `/app/`

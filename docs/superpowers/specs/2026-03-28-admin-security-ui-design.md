# Admin Security UI: Groups & Policy Grants Management

## Problem

The database-backed permissions system (groups and policy grants) has REST API endpoints but no admin UI. Administrators must use curl or API tools to manage groups and policy grants. The existing admin panel has Users and Content tabs but nothing for security management.

## Scope

**In scope:**
- New "Security" tab in the admin panel with two sub-sections: Groups and Policy Grants
- Groups list view with search, create, edit, delete
- Groups edit modal with structured member list (add/remove rows)
- Policy grants list view with create, edit, delete
- Policy grants edit modal with context-sensitive action checkboxes
- Admin group protections reflected in UI (delete disabled, empty-save blocked)
- API client extensions for the new endpoints

**Out of scope:**
- Changes to the REST API endpoints (already built)
- Changes to the permission model or authorization flow
- User management changes (already has its own tab)

## Design

### Navigation

The admin panel tab bar gains a third tab: **Users | Content | Security**. The Security tab contains two sub-section toggles (same pattern as Content's Dashboard/Orphaned/Broken/Versions): **Groups** and **Policy Grants**.

Route structure:
```
/admin/security          → SecurityPage (default: Groups sub-section)
```

AdminLayout nav links updated from 2 tabs to 3.

### Groups Sub-Section

**List view:**
- Sortable, searchable table with columns: Name, Members (comma-separated preview), Count (badge), Actions
- "Create Group" button in toolbar
- Action buttons per row: Edit, Delete
- Admin group: Delete button is disabled with tooltip "Cannot delete Admin group"
- Search filters by group name

**Edit/Create modal:**
- Group name field (disabled on edit, editable on create)
- Members section: bordered list of current members, each with a "Remove" button
- "Add member" row: text input + "Add" button
- Admin group: cannot save with zero members (server returns error, UI shows message)
- Cancel and Save buttons

### Policy Grants Sub-Section

**List view:**
- Table with columns: Principal (type badge + name), Type (page/wiki/group), Target, Actions (as chips), Edit/Delete buttons
- "Add Grant" button in toolbar
- AllPermission (`*`) shown with distinct styling (gold chip, bold)
- No search needed (typically < 20 rows)

**Edit/Create modal:**
- Principal Type: dropdown (role, group)
- Principal Name: text input (with hint listing built-in roles: All, Anonymous, Asserted, Authenticated, Admin)
- Permission Type: dropdown (page, wiki, group)
- Target: text input (default: `*`)
- Actions: checkbox grid that changes based on Permission Type:
  - page → view, comment, edit, modify, upload, rename, delete
  - wiki → createPages, createGroups, editPreferences, editProfile, login
  - group → view, edit
- Helper text below checkboxes: "Showing {type} actions. Change Permission Type to see others."
- Cancel and Save buttons

### Files

| File | Change |
|------|--------|
| `wikantik-frontend/src/components/admin/AdminSecurityPage.jsx` | NEW — Security tab with Groups and Policy Grants sub-sections |
| `wikantik-frontend/src/components/admin/GroupFormModal.jsx` | NEW — Create/edit group modal with member list |
| `wikantik-frontend/src/components/admin/PolicyGrantFormModal.jsx` | NEW — Create/edit policy grant modal with context-sensitive checkboxes |
| `wikantik-frontend/src/components/admin/AdminLayout.jsx` | MODIFY — add Security tab to nav |
| `wikantik-frontend/src/main.jsx` | MODIFY — add /admin/security route |
| `wikantik-frontend/src/api/client.js` | MODIFY — add admin.listGroups, getGroup, createGroup, updateGroup, deleteGroup, listPolicyGrants, createPolicyGrant, updatePolicyGrant, deletePolicyGrant |
| `wikantik-frontend/src/components/Sidebar.jsx` | MODIFY — add Security link under admin section (if admin links are listed there) |

### Component Patterns

All new components follow the established patterns from AdminUsersPage and UserFormModal:
- `useState` for data, loading, error, search, sort, modal state
- `useEffect` for initial data load via `api.admin.*` calls
- `useMemo` for filtered/sorted display data
- Error display via `.error-banner` class
- Success/error feedback via `.admin-message` class
- Modals via `.modal-overlay` / `.modal-content` with scaleIn animation
- Tables via `.admin-table` with existing cell classes
- Buttons via `.btn`, `.btn-primary`, `.btn-ghost`, `.btn-sm`, `.btn-danger`
- No new CSS required — all existing admin styles are sufficient

### API Client Extensions

```javascript
api.admin.listGroups()                    // GET /admin/groups
api.admin.getGroup(name)                  // GET /admin/groups/{name}
api.admin.updateGroup(name, data)         // PUT /admin/groups/{name}
api.admin.deleteGroup(name)               // DELETE /admin/groups/{name}
api.admin.listPolicyGrants()              // GET /admin/policy
api.admin.createPolicyGrant(data)         // POST /admin/policy
api.admin.updatePolicyGrant(id, data)     // PUT /admin/policy/{id}
api.admin.deletePolicyGrant(id)           // DELETE /admin/policy/{id}
```

### Error Handling

- Admin group delete: server returns 400 with message, UI shows in error banner
- Admin group save with zero members: server returns 400, UI shows message
- Invalid policy grant actions: server returns 400, UI shows validation error
- Network errors: caught in try/catch, displayed in error banner
- Confirmation dialog before all delete operations

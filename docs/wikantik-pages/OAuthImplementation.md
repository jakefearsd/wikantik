---
type: article
tags:
- uncategorized
summary: OAuth SSO Implementation Plan for Google/GitHub Logins
---
1. OAuth SSO Implementation Plan for Google/GitHub Logins

  1. Executive Summary

Wikantik's JAAS-based architecture is **well-suited for OAuth integration**. The key insight is that passwords are optional in the user database, so OAuth users can be created without passwords and never use password-based login.

---

  1. Required Components

    1. 1. New Java Classes (~6-8 files)

| File | Purpose |
|------|---------|
| `OAuthLoginModule.java` | JAAS LoginModule that validates tokens and creates/links users |
| `OAuthCallbackHandler.java` | Passes OAuth token to LoginModule via JAAS callbacks |
| `OAuthCallback.java` | Custom JAAS Callback for OAuth data |
| `OAuthCallbackServlet.java` | Handles `/wiki/oauth/callback` redirect from providers |
| `GoogleOAuthProvider.java` | Validates Google tokens, fetches user info |
| `GitHubOAuthProvider.java` | Validates GitHub tokens, fetches user info |
| `OAuthUserInfo.java` | DTO for user info from providers |

    1. 2. UI Modifications

- **LoginContent.jsp**: Add "Login with Google" and "Login with GitHub" buttons
- Buttons link to `/wiki/oauth/google?redirect=...` and `/wiki/oauth/github?redirect=...`

    1. 3. Configuration (wikantik.properties)

```properties
1. OAuth SSO Configuration
jspwiki.oauth.enabled=true
jspwiki.oauth.autoCreateUsers=true

1. Google OAuth 2.0 / OpenID Connect
jspwiki.oauth.google.enabled=true
jspwiki.oauth.google.clientId=YOUR_CLIENT_ID
jspwiki.oauth.google.clientSecret=YOUR_CLIENT_SECRET

1. GitHub OAuth 2.0
jspwiki.oauth.github.enabled=true
jspwiki.oauth.github.clientId=YOUR_CLIENT_ID
jspwiki.oauth.github.clientSecret=YOUR_CLIENT_SECRET
```

    1. 4. Dependencies (pom.xml)

- OAuth 2.0 client library (e.g., `google-oauth-client` or `oltu.oauth2`)
- JWT validation (for Google ID tokens)
- HTTP client for API calls

---

  1. OAuth Flow Integration

```
1. User clicks "Login with Google" on LoginContent.jsp
                    ↓
2. OAuthCallbackServlet redirects to Google consent screen
                    ↓
3. User authenticates with Google
                    ↓
4. Google redirects to /wiki/oauth/callback?code=...&state=...
                    ↓
5. OAuthCallbackServlet:
   - Exchanges code for access token
   - Fetches user info (email, name, provider ID)
   - Creates OAuthCallbackHandler with token data
   - Calls AuthenticationManager with OAuthLoginModule
                    ↓
6. OAuthLoginModule.login():
   - Gets token data from callback handler
   - Looks up user by email in UserDatabase
   - If not found: creates new UserProfile (no password)
   - Adds WikiPrincipal to principals set
                    ↓
7. WikiSession.actionPerformed():
   - Receives LOGIN_AUTHENTICATED event
   - Sets session status to AUTHENTICATED
   - Injects user profile principals
   - Injects group memberships
                    ↓
8. Redirect to original page
```

---

  1. User Account Creation Strategy

    1. Option A: Email-Based Linking (Recommended)

```java
// In OAuthLoginModule.login()
UserProfile profile;
try {
    profile = db.findByEmail(oauthUserInfo.getEmail());
} catch (NoSuchPrincipalException e) {
    // Create new user
    profile = db.newProfile();
    profile.setLoginName(generateLoginName(oauthUserInfo));
    profile.setEmail(oauthUserInfo.getEmail());
    profile.setFullname(oauthUserInfo.getName());
    profile.setPassword(null);  // No password for OAuth users

    // Store OAuth metadata in custom attributes
    profile.getAttributes().put("oauth.provider", "google");
    profile.getAttributes().put("oauth.providerId", oauthUserInfo.getId());

    db.save(profile);
}
```

    1. Login Name Generation Options

- `google_123456789` (provider + ID)
- `john.smith` (email local part)
- `jsmith` (truncated)

---

  1. Key Technical Insights

    1. 1. Password-less Users Work Out-of-Box

`UserDatabaseLoginModule.login()` line 86 checks:
```java
if (storedPassword != null && db.validatePassword(...))
```
OAuth users with `null` password bypass password validation entirely.

    1. 2. Session Establishment is Automatic

Just fire the event and WikiSession handles everything:
```java
fireEvent(WikiSecurityEvent.LOGIN_AUTHENTICATED, principal, session);
```

    1. 3. Configuration is Already Flexible

`DefaultAuthenticationManager.initLoginModuleOptions()` loads all `jspwiki.loginModule.options.*` properties and passes them to the LoginModule.

    1. 4. User Database Abstraction Works Well

Both XMLUserDatabase and JDBCUserDatabase support:
- `findByEmail(String)` - Look up existing users
- `save(UserProfile)` - Create new users
- Custom attributes map for OAuth metadata

---

  1. Challenges & Solutions

| Challenge | Solution |
|-----------|----------|
| **Same person, multiple providers** | Link by email; store provider info in attributes |
| **Incomplete profile data** | Compute full name from email; prompt for completion |
| **Token expiry** | Store refresh token (encrypted) in attributes |
| **Logout coordination** | Clear local session; optionally revoke provider token |
| **Account security** | Require email verification; admin account linking tool |

---

  1. Estimated Effort

| Phase | Effort | Description |
|-------|--------|-------------|
| **Core OAuth** | 3-4 days | LoginModule, CallbackHandler, Servlet, Providers |
| **User provisioning** | 1-2 days | Account creation, email linking, attributes |
| **UI** | 1 day | Login buttons, styling |
| **Configuration** | 0.5 day | Properties, documentation |
| **Testing** | 2-3 days | Unit tests, integration tests |
| **Total** | ~8-10 days | For experienced developer |

---

  1. Architecture Decision: Servlet + LoginModule Hybrid

  - Recommended approach**:

1. **OAuthCallbackServlet** handles:
   - OAuth redirect flow
   - Authorization code exchange
   - Token validation
   - Calling provider APIs

2. **OAuthLoginModule** handles:
   - User lookup/creation in database
   - Principal establishment
   - JAAS integration

This separation keeps OAuth protocol details out of JAAS and allows the LoginModule to focus on user management.

---

  1. Files to Modify (Existing)

| File | Change |
|------|--------|
| `wikantik.properties` | Add OAuth configuration properties |
| `LoginContent.jsp` | Add OAuth login buttons |
| `web.xml` | Register OAuthCallbackServlet |
| `pom.xml` | Add OAuth dependencies |

---

  1. Key Code Locations

    1. Authentication Flow
- Entry: `WikiServletFilter.doFilter()` line 120
- Manager: `DefaultAuthenticationManager.login()` lines 156-240
- Session: `WikiSession.getWikiSession()` line 483
- Event handling: `WikiSession.actionPerformed()` line 244

    1. User Management
- Profile interface: `/auth/user/UserProfile.java`
- Profile impl: `/auth/user/DefaultUserProfile.java`
- Database interface: `/auth/user/UserDatabase.java`
- Database impl: `/auth/user/XMLUserDatabase.java`, `/auth/user/JDBCUserDatabase.java`

    1. Login Modules
- Abstract: `/auth/login/AbstractLoginModule.java`
- User database: `/auth/login/UserDatabaseLoginModule.java` lines 66-108
- Callback base: `/auth/login/WikiCallbackHandler.java`

    1. Configuration
- Properties loaded: `DefaultAuthenticationManager.initialize()` line 110
- Options extraction: `DefaultAuthenticationManager.initLoginModuleOptions()` line 375

    1. UI
- Login page: `/wikantik-war/src/main/webapp/Login.jsp`
- Login form: `/wikantik-war/src/main/webapp/templates/default/LoginContent.jsp`

---

  1. Conclusion

Implementing OAuth SSO for Google/GitHub in Wikantik is **architecturally straightforward** due to:

1. **Pluggable JAAS LoginModules** - No changes to auth framework needed
2. **Optional passwords** - OAuth users created without passwords work perfectly
3. **Automatic session setup** - Fire event, session handles the rest
4. **Flexible user database** - Custom attributes for OAuth metadata

The main work is implementing the OAuth protocol (token exchange, API calls) and user provisioning logic. The Wikantik authentication infrastructure supports this cleanly.

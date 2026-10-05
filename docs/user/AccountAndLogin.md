# Account and Login

This guide is for anyone who signs in to the wiki. It covers signing in with a password or single sign-on, resetting a forgotten password, the forced password change on first login, editing your profile, creating personal API keys, and signing out. For the sidebar panel you see once signed in, see [Personal zone and preferences](PersonalZone.md).

## Sign in

Open the **Sign in** dialog (or go to `/login`). Enter your username and password and choose **Sign in**. A wrong username or password shows "Invalid credentials".

If your wiki has single sign-on turned on, a **Continue with ...** button appears above the form, labelled with your provider's name. Choose it to sign in at the provider. The dialog notes that signing in the first time creates your account. A single sign-on account has no wiki password, so password reset and password change do not apply to it. Administrators configure single sign-on; see [Single Sign-On](../admin/SingleSignOn.md).

## Reset a forgotten password

1. In the sign-in dialog choose **Forgot your password?** (or go to `/reset-password`).
2. Enter the email address on your account and submit.
3. The page always answers "If an account exists with that email, a new password has been sent", whether or not the address is known. Check your inbox and sign in with the new password. Because the new password was assigned, not chosen, the wiki then sends you to the change-password page (see below).

A single email address can request at most 3 resets per hour. Further requests in that hour get the same message but no email is sent.

This only works if an administrator has configured outgoing mail.

## Change your password on first login or after a reset

Some accounts have an assigned password: the first administrator login, and any account whose password was just reset by email. For these, the wiki sends you to `/change-password` ("Change Your Password") straight after sign-in, and from any page until you finish. Enter the current password, a new password and the new password again. They must match. When it succeeds you go to the Main page.

## Edit your profile and password

Go to **Profile** in the sidebar, or `/preferences`.

| Field | Notes |
|---|---|
| Login Name, Wiki Name | Read-only. |
| Full Name, Email | Editable. The email is where password resets are sent. |
| Bio | Editable, up to 1000 characters. |
| Current / New / Confirm New Password | Leave blank to keep your current password. |

At the bottom, the **Danger Zone** deletes your account. It is permanent, and you confirm by typing your login name. Pages you contributed stay on the wiki and stay attributed to your username.

## Create a personal API key

The **API Keys** section on the profile page creates keys for scripts and AI clients. A key acts with your own permissions, so it can never do more than you can.

1. Enter an optional label, pick a scope, and choose to generate the key.
2. Copy the secret straight away. It is shown once.
3. Use **Rotate** to replace a key with a new secret (same label and scope), or **Revoke** to disable it. Anything still using a revoked key gets HTTP 403.

You can create keys with scope `tools` or `mcp_read` (default). The scope defaults to `mcp_read` when you leave it blank.

If you need `mcp` or `all` scope — for admin-level access to the full MCP endpoint or all wiki surfaces — ask an administrator to create those keys for you. For details on other scopes and key management, see [API keys](../admin/ApiKeys.md).

Administrators manage keys for other users; see [API keys](../admin/ApiKeys.md).

## Sign out

Choose **Sign out** under your name at the top of the sidebar.

## See also

- [Personal zone and preferences](PersonalZone.md)
- [Single Sign-On](../admin/SingleSignOn.md)
- [API keys](../admin/ApiKeys.md)

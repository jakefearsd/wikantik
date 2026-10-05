# Account and Login

This guide is for anyone who signs in to the wiki. It covers signing in with a password or single sign-on, resetting a forgotten password, the forced password change on first login, editing your profile, creating personal API keys, and signing out. For the sidebar panel you see once signed in, see [Personal zone and preferences](PersonalZone.md).

## Sign in

Open the **Sign in** dialog (or go to `/login`). Enter your username and password and choose **Sign in**. A wrong username or password shows "Invalid credentials".

If your wiki has single sign-on turned on, a **Continue with ...** button appears above the form, labelled with your provider's name. Choose it to sign in at the provider. The dialog notes that signing in the first time creates your account. A single sign-on account has no wiki password, so password reset and password change do not apply to it. Administrators configure single sign-on; see [Single Sign-On](../admin/SingleSignOn.md).

## Reset a forgotten password

1. In the sign-in dialog choose **Forgot your password?** (or go to `/reset-password`).
2. Enter the email address on your account and submit.
3. The page always answers "If an account exists with that email, a new password has been sent", whether or not the address is known. Check your inbox, sign in with the new password, and then change it on your profile page.

This only works if an administrator has configured outgoing mail.

## Change your password on first login

Some accounts are created with an assigned password, for example the first administrator login. For these, the wiki sends you to `/change-password` ("Change Your Password") straight after sign-in, and from any page until you finish. Enter the current password, a new password and the new password again. They must match. When it succeeds you go to the Main page.

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

The scope chooser offers four values:

| Scope | Use |
|---|---|
| `tools` | The OpenAPI tool endpoints (`/tools/*`). |
| `mcp_read` | Read-only access to the knowledge MCP endpoint. |
| `mcp` | The full admin MCP endpoint. Choose it only for clients that must write. |
| `all` | Every surface. |

Administrators manage keys for other users; see [API keys](../admin/ApiKeys.md).

## Sign out

Choose **Sign out** under your name at the top of the sidebar.

## See also

- [Personal zone and preferences](PersonalZone.md)
- [Single Sign-On](../admin/SingleSignOn.md)
- [API keys](../admin/ApiKeys.md)

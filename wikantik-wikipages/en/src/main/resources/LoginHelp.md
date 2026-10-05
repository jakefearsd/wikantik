# Login Help

How to sign in, recover a password and manage your account. For the full guide, see [Account and Login](https://github.com/jakefearsd/wikantik/blob/main/docs/user/AccountAndLogin.md).

## Sign in

Choose **Sign in** in the sidebar, enter your username and password, and choose **Sign in** again. If your wiki has single sign-on, a **Continue with ...** button appears above the form; use it to sign in at your provider. The first single sign-on login creates your account, and such an account has no wiki password.

You can read pages without signing in. What you may edit depends on your account and on each page's access rules.

## Forgot your password

1. Choose **Forgot your password?** in the sign-in dialog.
2. Enter the email address on your account.
3. Sign in with the new password from the email. A reset password is assigned, not chosen, so the wiki then asks you to set a new one before you continue.

The page gives the same answer whether or not the address is known, and one address can request a few resets per hour. Resets only work if an administrator has set up outgoing mail.

## Change your password

The first administrator login and any reset password send you to **Change Your Password** straight after signing in. You can also change it later on your profile page.

## Your profile and API keys

Choose **Profile** in the sidebar (or open `/preferences`) to edit your full name, email and bio, change your password, and delete your account.

The **API Keys** section creates keys for scripts and AI clients. A key acts with your own permissions. A regular account can create keys with the `tools` scope (the `/tools/*` endpoints) or the `mcp_read` scope (read-only knowledge MCP access); keys with wider scope are created by an administrator. The secret is shown once, so copy it when you create it.

## Sign out

Choose **Sign out** under your name at the top of the sidebar.

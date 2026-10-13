---
type: reference
cluster: wikantik-development
tags:
- privacy
- policy
- legal
- wikantik
title: Privacy Policy
summary: 'How wiki.wikantik.com handles your data: what it collects, which cookies
  it sets, who else sees it, and how to delete your account.'
canonical_id: 01KQ0P44TRZS26YNJNHK32EFF1
status: active
date: '2026-10-13'
---
# Privacy Policy

**Effective:** 2026-10-13

This is the privacy policy for **wiki.wikantik.com**, the public Wikantik wiki. I run it: Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com). Wikantik is the wiki software; this policy covers this site and the contact form on the www.wikantik.com marketing site. If you run your own copy of Wikantik, this policy says nothing about your copy.

## The short version

- I do not sell your data. The wiki shows no ads and loads no analytics or advertising code.
- You can read most of the wiki without an account. Even then, your IP address and browser details pass through Cloudflare and are written to server logs.
- An account holds the basics: login name, email address, display name and, for password accounts, a hashed password.
- Edits and comments are public, along with the name they were made under. Page history is kept.
- You can delete your own account. Your past contributions stay in the page history.

## What the wiki collects

**Visiting.** Every request reaches the wiki through Cloudflare, which sees your IP address and request headers. The wiki server then keeps ordinary logs: your IP address (as Cloudflare reports it), the time, the address you asked for, the response status and size, the country Cloudflare reports for the connection, and your browser's user-agent string. Application log lines written while handling your request can carry the same details plus a random request ID.

**Searching.** When you submit a search, the wiki saves the query text, whether it looks like a person or an automated agent, which search feature was used, how many results came back, and the time. It does not store your login name, IP address or cookie with the query. I read these to see what people look for and what the wiki is missing. The as-you-type suggestions in the quick-search box are not saved; only searches you submit are.

**An account.** The wiki stores your login name, email address, display (full) name, a wiki name, when the account was created and last changed, and when you last signed in. You can add a short bio (up to 1,000 characters), which only you and administrators can see. A password account stores a bcrypt hash of the password, never the password itself. An account created through Google sign-in has no password. If you create an API key, the wiki stores only a hash of the key, plus its label and the dates it was created and last used.

**What you write.** Page edits, comments and file attachments are stored with the name they were made under: page history shows your display name, and comments show your login name. Most pages are public, so anyone can read them and their history. Comments and attachments are visible to whoever can see the page.

**Your names are not secret.** Active accounts can be looked up by login name or display name through the @mention box in comments, and that lookup works without signing in. If you sign in with Google, your login name is your Google email address, so your email address can appear in comments and in that lookup. If that is a problem for you, write to me before you create an account. Reading the wiki never needs one.

**An audit log.** The wiki keeps an append-only record of security-relevant events: sign-ins and sign-outs (successful and failed), access denied, page saves, deletes and renames, group and profile changes, and API key events. Each entry records who did it, what happened, the IP address and the browser's user-agent. Page reads are not recorded unless a page is specifically flagged for it. Entries are not edited or removed when an account is deleted.

## Cookies and browser storage

| Name | Set when | What it does | Lifetime |
|---|---|---|---|
| `JSESSIONID` | You load a wiki page or sign in | Keeps your session, including being signed in | Until you close the browser; the server drops an idle session after 60 minutes. Secure, HttpOnly, SameSite=Lax |
| `WikantikUID` | You sign in with a password (not with Google) | The "stay signed in" cookie: a random ID, with the server remembering which login name it belongs to, so you are signed back in after a server restart or a session timeout | 14 days, or until you sign out. Secure, HttpOnly, SameSite=Lax |

This site has the "stay signed in" feature turned on, so `WikantikUID` is set every time you sign in with a password.

There are no analytics, advertising or cross-site tracking cookies. The site also keeps a few things in your browser's own storage, which is not sent to the server: your theme and editor settings, which panels are open, your recent searches and recently viewed pages, unsaved editor drafts, and whether Google sign-in is offered. Your browser's settings let you clear all of it.

## Who else gets your data

**Cloudflare.** The wiki runs on a server I operate myself, and public traffic reaches it through a Cloudflare tunnel. Cloudflare therefore processes every request, including your IP address and headers. Its practices are in [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).

**Google Fonts.** The page design uses fonts that your browser downloads from Google (fonts.googleapis.com and fonts.gstatic.com) after the page has loaded. Google receives your IP address, your browser details and the address of the page you are on. If you block those requests, the wiki still works, with your browser's fallback fonts. See [Google's privacy policy](https://policies.google.com/privacy).

**Google sign-in (only if you choose it).** The wiki asks Google for your basic profile and email address. It reads three things from the answer: your Google account's unique ID (to recognise you next time), your email address and your name. It creates your account from them: login name and email are your email address, and display name is your Google name. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run your account. Google learns that you signed in to this wiki. If you never choose Google sign-in, nothing is sent to Google except the fonts above.

**Email.** The only email the wiki can send is a password-reset message to the address on the account. The production wiki currently has no outgoing mail service configured, so it sends no email at all. If I turn that on, mail will go through an outside mail provider and I will name it here.

**Search engines and crawlers.** Public pages are meant to be found. I publish a sitemap and can tell search engines about new or changed public page addresses through the IndexNow service (addresses only, nothing personal). Search engines and other crawlers can copy anything that is public, including page history with the names on edits, and I cannot recall those copies.

**AI models.** The wiki uses AI models to build its search index and to pull a topic graph out of page text. Those models run on computers I own. Page text is not sent to an outside AI service.

**Backups.** Backups contain a copy of the whole database and all pages, so they hold everything described on this page. Copies are kept on the wiki server, on a separate storage device at my home, and in a folder on that device that syncs to my personal Google Drive.

**Other websites.** Pages can link to, and embed images from, other websites. When a page embeds an image, your browser fetches it from that site directly. Their privacy practices are theirs.

**The www.wikantik.com contact form.** The form asks for your name, email address and what you would use Wikantik for. Submitting it sends those to a Google Apps Script that adds a row to a Google Sheet in my Google account. I use it only to reply to you.

## How long things are kept

- **Logs:** weeks, not years. The central log store deletes entries after 30 days, the security log keeps at most 90 daily files, and the web server's own access log lives inside the application container and goes away when a new release replaces that container.
- **Audit log:** up to seven years.
- **Search queries:** no automatic deletion.
- **Account details:** until you delete the account.
- **Page history, comments and attachments:** kept indefinitely.
- **Backups:** daily copies for 30 days, weekly for 12 weeks and monthly for 12 months on the wiki server. The home storage device keeps them longer, up to about a year. Deleted data therefore remains in older backups until they age out.

## Your choices

- **Change your details.** Sign in and open [your preferences](https://wiki.wikantik.com/preferences) to change your display name, email address, bio or password. Login name and wiki name are fixed once the account exists.
- **Delete your account.** The Danger Zone at the bottom of the same page deletes it after you type your login name to confirm. That removes your profile (login name, email, names, bio and password hash) and your group memberships, revokes your API keys and signs you out. If you later sign in with Google again, a new account is created.
- **What deletion does not remove.** Your past edits and comments stay in the wiki, under the name they were made with. Audit log entries stay. Backups keep the old data until they age out, and anything search engines or others have already copied stays copied.
- **Ask me.** Write to me for a copy of what your account holds, to correct something, or to ask about removing your name from old contributions. I will look at each request and tell you what can be done.

## Security

The site is served over HTTPS only. Passwords and API keys are stored only as hashes. The server accepts no inbound connections from the internet; traffic arrives through the Cloudflare tunnel. No system is perfectly secure, and I cannot promise otherwise.

## Children

This site is not directed at children under 13, and I do not knowingly collect their information. If you think a child has an account, write to me and I will remove it.

## Changes

When I change this policy I will update the effective date at the top, and the page history is public. 2026-10-13: rewritten from a generic template to describe what the wiki actually does.

## Contact

Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com).

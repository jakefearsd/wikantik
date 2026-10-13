---
date: '2026-05-04'
status: active
title: Sending Email from the Wiki
related:
- PostgreSQLLocalDeployment
- DockerDeployment
canonical_id: 01KQ0P44W8B80CNNQM2VFNCFP0
cluster: wikantik-development
type: article
tags:
- email
- smtp
- wikantik-development
- configuration
- transactional-email
summary: 'Configure Wikantik transactional email (welcome, password reset, admin notices)
  via SMTP relay: Brevo, SendGrid, Mailjet, Amazon SES, and JNDI session setup.'
---
# Sending Email from the Wiki

This guide explains how to configure Wikantik to send transactional emails from a self-hosted installation. Wikantik sends mail in three situations: a welcome email to a user when their profile is created, a password-reset email that carries a temporary password (the user must change it at the next login), and an optional notice to an administrator whenever a new profile is created (`wikantik.admin.notification.email`). It does not send a separate address-verification email.

## Why You Need an Email Relay Service

Sending email directly from a home server will fail because:
- Residential IPs are universally blacklisted by spam filters
- ISPs typically block outbound port 25
- No reverse DNS (PTR) record for your IP
- Emails will land in spam or be rejected outright

**You need a transactional email relay service.**

---

## Recommended Email Services

### 1. Brevo (formerly Sendinblue) — Best Free Tier

- **Free**: 300 emails/day (9,000/month)
- Excellent deliverability
- Simple SMTP setup
- No credit card required for free tier
- Website: https://www.brevo.com

### 2. SendGrid (Twilio) — Most Popular

- **Free**: 100 emails/day forever (3,000/month)
- Very reliable, well-documented
- Requires credit card even for free tier
- Website: https://sendgrid.com

### 3. Mailjet

- **Free**: 200 emails/day (6,000/month)
- Good deliverability
- Easy setup
- Website: https://www.mailjet.com

### 4. Amazon SES — Cheapest at Scale

- **Cost**: \$0.10 per 1,000 emails
- Requires AWS account
- More complex setup, but excellent once configured
- Website: https://aws.amazon.com/ses/

### 5. Resend — Modern Option

- **Free**: 3,000 emails/month
- Modern API, developer-friendly
- Good deliverability
- Website: https://resend.com

**For low volume (few hundred emails per week), Brevo or SendGrid free tiers are more than sufficient.**

---

## Step-by-Step Setup

This example uses Brevo, but the process is similar for other providers.

### Step 1: Sign Up for Email Service

1. Go to https://www.brevo.com
2. Create a free account
3. Verify your email address

### Step 2: Add and Verify Your Sending Domain

1. In Brevo dashboard, go to **Senders & IP** → **Domains**
2. Add your domain (e.g., `jakefear.com` or `wiki.jakefear.com`)
3. Brevo will provide DNS records to add

### Step 3: Configure DNS Records

Add the DNS records provided by your email service. Typical records include:

| Type | Host | Value | Purpose |
|------|------|-------|---------|
| TXT | `@` | `v=spf1 include:sendinblue.com ~all` | SPF - authorizes service to send |
| TXT | `mail._domainkey` | `k=rsa; p=MIGf...` | DKIM - email signing |
| TXT | `_dmarc` | `v=DMARC1; p=quarantine; rua=mailto:...` | DMARC - policy |

**Note**: If you already have an SPF record, merge them:
```
v=spf1 include:existing.com include:sendinblue.com ~all
```

### Step 4: Get SMTP Credentials

In your email service dashboard:
1. Navigate to SMTP settings
2. Note down:
   - SMTP Server (e.g., `smtp-relay.brevo.com`)
   - Port: `587` (TLS) or `465` (SSL)
   - Login/Username
   - Password or API Key

### Step 5: Configure Wikantik

Add to your `wikantik-custom.properties` (bare-metal Tomcat: `tomcat/tomcat-11/lib/wikantik-custom.properties`):

```properties
# Email configuration
mail.from = Wikantik <wiki@yourdomain.com>
mail.smtp.host = smtp-relay.brevo.com
mail.smtp.port = 587
mail.smtp.account = your-login@email.com
mail.smtp.password = your-smtp-key-here
mail.smtp.starttls.enable = true
mail.smtp.timeout = 5000
mail.smtp.connectiontimeout = 5000
```

**In the container stack** you do not edit the properties file: set `MAIL_SMTP_HOST`, `MAIL_SMTP_PORT` (default 587), `MAIL_SMTP_ACCOUNT`, `MAIL_SMTP_PASSWORD` and `MAIL_FROM` in `.env`. Whenever `MAIL_SMTP_HOST` is non-empty, `docker/entrypoint.sh` writes the matching `mail.*` properties (including `mail.smtp.starttls.enable = true`) into the generated `wikantik-custom.properties` on every start. Leave `MAIL_SMTP_HOST` empty to leave mail unconfigured. See [Wikantik on Docker](WikantikOnDocker).

### Step 6: Test Email

1. Restart Tomcat (or the container)
2. Create a user account that has an email address, or use the password-reset flow (`/reset-password`) for an existing account
3. Check that the welcome or password-reset email arrives
4. Look for `Sent e-mail to=...` in `wikantik.log`, which also says whether the JNDI or the standalone session was used
5. Check your email service dashboard for delivery logs

---

## Alternative: JNDI Configuration (More Secure)

To keep credentials out of properties files, configure the mail session in Tomcat's context file.

Add to your Tomcat context file `conf/Catalina/localhost/ROOT.xml` (Wikantik is deployed as the ROOT context; `bin/deploy-local.sh` renders this file once and does not overwrite it afterwards). This route is for bare-metal Tomcat only: the container's entrypoint regenerates `ROOT.xml` on every start, so use the `MAIL_SMTP_*` variables there.

```xml
<Resource name="mail/Session"
          auth="Container"
          type="jakarta.mail.Session"
          mail.smtp.host="smtp-relay.brevo.com"
          mail.smtp.port="587"
          mail.smtp.auth="true"
          mail.smtp.starttls.enable="true"
          mail.smtp.user="your-login@email.com"
          password="your-smtp-key-here"
          mail.from="wiki@yourdomain.com"/>
```

Wikantik will automatically use the JNDI session `mail/Session` when available.

---

## SMTP Settings by Provider

### Brevo (Sendinblue)

```properties
mail.smtp.host = smtp-relay.brevo.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

### SendGrid

```properties
mail.smtp.host = smtp.sendgrid.net
mail.smtp.port = 587
mail.smtp.account = apikey
mail.smtp.password = SG.your-api-key-here
mail.smtp.starttls.enable = true
```

### Mailjet

```properties
mail.smtp.host = in-v3.mailjet.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

### Amazon SES

```properties
mail.smtp.host = email-smtp.us-east-1.amazonaws.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

---

## Wikantik Mail Properties Reference

| Property | Default | Description |
|----------|---------|-------------|
| `mail.from` | `Wikantik <Wikantik@localhost>` (the shipped `ini/wikantik.properties` value; the code falls back to `wikantik@localhost`) | The sender email address |
| `mail.smtp.host` | `127.0.0.1` | SMTP server hostname |
| `mail.smtp.port` | `25` | SMTP server port |
| `mail.smtp.account` | (not set) | SMTP username for authentication; setting it turns SMTP authentication on |
| `mail.smtp.password` | (not set) | SMTP password for authentication |
| `mail.smtp.starttls.enable` | not applied unless set | Enable TLS encryption. The shipped ini lists `true` as the default, but the standalone session passes only the `mail.smtp.*` keys you actually set to JavaMail (whose own default is off), so set it to `true` explicitly for a port-587 relay |
| `mail.smtp.timeout` | not applied unless set | Socket I/O timeout (ms); the ini lists 5000 but it only takes effect when set |
| `mail.smtp.connectiontimeout` | not applied unless set | Connection timeout (ms); the ini lists 5000 but it only takes effect when set |
| `wikantik.mail.jndiname` | `mail/Session` | JNDI name for container-managed session; Wikantik tries it first and falls back to the `mail.*` properties if the lookup fails |
| `wikantik.admin.notification.email` | (blank) | When set, an email is sent to this address each time a new user profile is created |

---

## Troubleshooting

### Emails Not Sending

1. Check the logs for mail-related errors (a failed registration mail logs `Could not send registration confirmation e-mail. Is the e-mail server running?`)
2. Verify SMTP credentials are correct
3. Ensure port 587 outbound is not blocked by firewall
4. Check email service dashboard for rejected/bounced messages

### Emails Going to Spam

1. Verify SPF, DKIM, and DMARC records are properly configured
2. Use a "from" address that matches your verified domain
3. Allow 24-48 hours for DNS changes to propagate
4. Check your domain's reputation at https://mxtoolbox.com

### Authentication Errors

1. Some services require API keys instead of passwords
2. SendGrid uses `apikey` as the username with API key as password
3. Ensure special characters in passwords are properly escaped

### Connection Timeouts

1. Try port 465 (SSL) instead of 587 (TLS)
2. Check if your ISP blocks outbound SMTP ports
3. Increase timeout values in configuration

---

## Related Documentation

- [PostgreSQL Local Deployment](PostgreSQLLocalDeployment) — Local development setup
- [Docker Deployment](DockerDeployment) and [Wikantik on Docker](WikantikOnDocker) — Container deployment guide

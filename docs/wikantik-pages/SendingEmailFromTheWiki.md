---
type: article
tags:
- ai
- technology
summary: This guide explains how to configure Wikantik to send transactional emails
  (account verification, password reset, notifications) from a self-hosted installation.
---
1. Sending Email from the Wiki

This guide explains how to configure Wikantik to send transactional emails (account verification, password reset, notifications) from a self-hosted installation.

  1. Why You Need an Email Relay Service

Sending email directly from a home server will fail because:
- Residential IPs are universally blacklisted by spam filters
- ISPs typically block outbound port 25
- No reverse DNS (PTR) record for your IP
- Emails will land in spam or be rejected outright

  - You need a transactional email relay service.**

---

  1. Recommended Email Services

    1. 1. Brevo (formerly Sendinblue) - Best Free Tier

- **Free**: 300 emails/day (9,000/month)
- Excellent deliverability
- Simple SMTP setup
- No credit card required for free tier
- Website: https://www.brevo.com

    1. 2. SendGrid (Twilio) - Most Popular

- **Free**: 100 emails/day forever (3,000/month)
- Very reliable, well-documented
- Requires credit card even for free tier
- Website: https://sendgrid.com

    1. 3. Mailjet

- **Free**: 200 emails/day (6,000/month)
- Good deliverability
- Easy setup
- Website: https://www.mailjet.com

    1. 4. Amazon SES - Cheapest at Scale

- **Cost**: $0.10 per 1,000 emails
- Requires AWS account
- More complex setup, but excellent once configured
- Website: https://aws.amazon.com/ses/

    1. 5. Resend - Modern Option

- **Free**: 3,000 emails/month
- Modern API, developer-friendly
- Good deliverability
- Website: https://resend.com

  - For low volume (few hundred emails per week), Brevo or SendGrid free tiers are more than sufficient.**

---

  1. Step-by-Step Setup

This example uses Brevo, but the process is similar for other providers.

    1. Step 1: Sign Up for Email Service

1. Go to https://www.brevo.com
2. Create a free account
3. Verify your email address

    1. Step 2: Add and Verify Your Sending Domain

1. In Brevo dashboard, go to **Senders & IP** → **Domains**
2. Add your domain (e.g., `jakefear.com` or `wiki.jakefear.com`)
3. Brevo will provide DNS records to add

    1. Step 3: Configure DNS Records

Add the DNS records provided by your email service. Typical records include:

| Type | Host | Value | Purpose |
|------|------|-------|---------|
| TXT | `@` | `v=spf1 include:sendinblue.com ~all` | SPF - authorizes service to send |
| TXT | `mail._domainkey` | `k=rsa; p=MIGf...` | DKIM - email signing |
| TXT | `_dmarc` | `v=DMARC1; p=quarantine; rua=mailto:...` | DMARC - policy |

  - Note**: If you already have an SPF record, merge them:
```
v=spf1 include:existing.com include:sendinblue.com ~all
```

    1. Step 4: Get SMTP Credentials

In your email service dashboard:
1. Navigate to SMTP settings
2. Note down:
   - SMTP Server (e.g., `smtp-relay.brevo.com`)
   - Port: `587` (TLS) or `465` (SSL)
   - Login/Username
   - Password or API Key

    1. Step 5: Configure Wikantik

Add to your `wikantik-custom.properties`:

```properties
1. Email configuration
mail.from = Wikantik <wiki@yourdomain.com>
mail.smtp.host = smtp-relay.brevo.com
mail.smtp.port = 587
mail.smtp.account = your-login@email.com
mail.smtp.password = your-smtp-key-here
mail.smtp.starttls.enable = true
mail.smtp.timeout = 5000
mail.smtp.connectiontimeout = 5000
```

    1. Step 6: Test Email

1. Restart Tomcat
2. Create a new user account in Wikantik
3. Check if verification email arrives
4. Check your email service dashboard for delivery logs

---

  1. Alternative: JNDI Configuration (More Secure)

To keep credentials out of properties files, configure the mail session in Tomcat's context file.

Add to your `Wikantik.xml` (in `conf/Catalina/localhost/`):

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

  1. SMTP Settings by Provider

    1. Brevo (Sendinblue)

```properties
mail.smtp.host = smtp-relay.brevo.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

    1. SendGrid

```properties
mail.smtp.host = smtp.sendgrid.net
mail.smtp.port = 587
mail.smtp.account = apikey
mail.smtp.password = SG.your-api-key-here
mail.smtp.starttls.enable = true
```

    1. Mailjet

```properties
mail.smtp.host = in-v3.mailjet.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

    1. Amazon SES

```properties
mail.smtp.host = email-smtp.us-east-1.amazonaws.com
mail.smtp.port = 587
mail.smtp.starttls.enable = true
```

---

  1. Wikantik Mail Properties Reference

| Property | Default | Description |
|----------|---------|-------------|
| `mail.from` | `${user.name}@${mail.smtp.host}` | The sender email address |
| `mail.smtp.host` | `127.0.0.1` | SMTP server hostname |
| `mail.smtp.port` | `25` | SMTP server port |
| `mail.smtp.account` | (not set) | SMTP username for authentication |
| `mail.smtp.password` | (not set) | SMTP password for authentication |
| `mail.smtp.starttls.enable` | `true` | Enable TLS encryption |
| `mail.smtp.timeout` | `5000` | Socket I/O timeout (ms) |
| `mail.smtp.connectiontimeout` | `5000` | Connection timeout (ms) |
| `jspwiki.mail.jndiname` | `mail/Session` | JNDI name for container-managed session |

---

  1. Troubleshooting

    1. Emails Not Sending

1. Check Tomcat logs for mail-related errors
2. Verify SMTP credentials are correct
3. Ensure port 587 outbound is not blocked by firewall
4. Check email service dashboard for rejected/bounced messages

    1. Emails Going to Spam

1. Verify SPF, DKIM, and DMARC records are properly configured
2. Use a "from" address that matches your verified domain
3. Allow 24-48 hours for DNS changes to propagate
4. Check your domain's reputation at https://mxtoolbox.com

    1. Authentication Errors

1. Some services require API keys instead of passwords
2. SendGrid uses `apikey` as the username with API key as password
3. Ensure special characters in passwords are properly escaped

    1. Connection Timeouts

1. Try port 465 (SSL) instead of 587 (TLS)
2. Check if your ISP blocks outbound SMTP ports
3. Increase timeout values in configuration

---

  1. Related Documentation

- [PostgreSQL Local Deployment](PostgreSQLLocalDeployment.md) - Local development setup
- [Wikantik Properties Reference](https://wiki.wikantik.com/Wiki.jsp?page=Documentation) - Full configuration options

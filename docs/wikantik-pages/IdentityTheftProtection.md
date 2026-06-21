---
type: article
status: active
cluster: personal-finance
date: '2026-04-26'
title: Identity Theft Protection
hubs:
- PersonalFinanceHub
tags:
- identity-theft
- security
- personal-finance
- credit
- fraud
summary: Practical layered defenses against identity theft — what actually reduces
  risk, what services duplicate things you already have for free, and what to do in
  the first 48 hours if you suspect theft.
related:
- PersonalFinanceGuide
- CreditScoreOptimization
- FinancialResilience
- InsuranceTypesAndCoverage
canonical_id: 01KQ0P44R1YDZQZKWH8PSA1PKW
---
# Identity Theft Protection

Identity theft is the unauthorized use of someone's personal information for financial fraud. The volume keeps rising — major data breaches now leak millions of records at a time, and most US adults' core data has been exposed at least once. The good news is that meaningful protection does not require paying a service. Most defenses are free or already included in things you have, and the actual financial liability of consumer-side identity theft is limited by federal law.

This page is about the practical layered defense, what actually reduces risk, and what to do in the first 48 hours if something happens.

## What identity theft actually costs you

Federal law caps your direct liability for unauthorized credit-card charges at \$50, and most issuers waive even that. For debit cards reported within 2 days of unauthorized use, liability is also capped at $50; reporting within 60 days caps it at \$500; after 60 days liability can be unlimited.

The real cost of identity theft is not direct losses. It is:

- **Time** — tens of hours fixing credit reports, disputing charges, freezing accounts
- **Stress** — months of vigilance during the resolution period
- **Indirect financial impact** — denied credit, higher rates while issues are unresolved, missed loan opportunities
- **Tax fraud cleanup** — the IRS issuing PIN numbers and resolving fraudulent returns can take 6+ months

The expected dollar loss for someone with a frozen credit report and basic vigilance is small. The expected time and stress cost can still be significant.

## The free, layered defense

The defense that protects most people most of the time is a stack of free tools. None of them alone is sufficient; together they cover the realistic threat space.

### Layer 1: credit freeze at all three bureaus

A credit freeze prevents new credit accounts from being opened in your name. Without a credit pull, the lender cannot verify the application; without verification, the loan does not close.

- **Cost**: free at all three bureaus (Equifax, Experian, TransUnion) since 2018
- **Effort**: ~15 minutes per bureau, set up online
- **Drawback**: you must temporarily lift the freeze before applying for credit yourself (a 5-minute online process)

Credit freezes are the single most effective defense against new-account fraud. Set them on yourself, your spouse, and any minor children. Place them now and forget about them.

### Layer 2: account-level fraud alerts and notifications

Every credit card and bank account offers some form of transaction notification. Enable:

- Email or text on every card transaction over $1
- Email on every login from a new device
- Email on any account-level change (address, phone, beneficiary)

Most fraud is caught quickly because the victim sees the email and knows it was not them. Catching fraud within 24 hours dramatically simplifies resolution.

### Layer 3: unique passwords per account

A breach at one service exposes the password used at that service. If the same password is used at another service, the attacker walks in. The attack is automated and routine.

Use a password manager (1Password, Bitwarden, or your browser's built-in manager). Generate unique random passwords for every account. The password manager stores them; you only need to remember one master password.

This single change is among the highest-leverage security improvements available. The marginal effort after setup is zero.

### Layer 4: two-factor authentication on critical accounts

For email, banks, brokerages, and password managers, enable two-factor authentication (2FA). Preferred order:

1. **Authenticator app** (Google Authenticator, Authy, 1Password's built-in TOTP) — best
2. **Hardware key** (YubiKey) — best for highest-value accounts
3. **SMS** — better than nothing but vulnerable to SIM-swap attacks

SMS 2FA is widely supported but not great. SIM-swap attacks (where attackers take over your phone number) defeat SMS 2FA. Where authenticator-app 2FA is available, prefer it.

### Layer 5: monitor your credit reports

Pull your free credit reports at annualcreditreport.com. Stagger the three bureaus — one every 4 months — for year-round visibility.

Look for:
- Accounts you do not recognize
- Hard inquiries you did not initiate
- Address or employer information that is wrong
- Personal information that does not match

### Layer 6: secure your email

Email is the master key to most online accounts because password resets go through email. If your email is compromised, almost every account is reachable.

For your primary email:
- Strong unique password
- 2FA via authenticator app
- Recovery information current
- Review the "active sessions" or "recent activity" page periodically

## The paid services and what they actually provide

The identity-protection-as-a-service market (LifeLock, IdentityForce, Aura, etc.) charges $10–$30/month for a bundle of services. The honest assessment:

| Service | Free alternative |
|---------|------------------|
| Credit monitoring | All three bureaus offer free monitoring; many credit cards offer it free |
| Dark web monitoring | HaveIBeenPwned (haveibeenpwned.com) is free and similar |
| SSN monitoring | Most credit-card issuers provide this |
| Identity-theft insurance | Often included in homeowners insurance and credit cards |
| Resolution services | The most useful component; would otherwise require self-service |

The case for paid services: the resolution services. If identity theft happens, having a service that handles the disputes, calls, and paperwork can save 30+ hours of work. For someone with the time, the free alternatives work. For someone who would rather pay to delegate the cleanup, $10–$15/month is reasonable.

The case against: most of what is sold is duplication. Free credit freezes are dramatically more effective than monitoring. Monitoring tells you something happened *after* it did; freezing prevents it from happening at all.

## What to do in the first 48 hours if you suspect identity theft

A specific action sequence:

### Hour 1
1. **Identify the affected accounts.** What was breached? Which accounts show suspicious activity?
2. **Change passwords** on those accounts and any account using the same password.
3. **Enable 2FA** if not already.

### Hour 2–4
4. **Place a fraud alert** with one credit bureau (the bureau is required to notify the other two). Free, lasts 1 year.
5. **Pull your credit reports** from all three bureaus to see what is on them.
6. **Notify card issuers and banks** of any unauthorized transactions. Cancel cards if needed; banks will issue new ones.

### Day 1–2
7. **File a report at IdentityTheft.gov** — this is the FTC's identity-theft portal. The output is an official identity-theft affidavit you can use with creditors and credit bureaus.
8. **File a police report** in your local jurisdiction. Some creditors require this.
9. **Place credit freezes** at all three bureaus (if you do not already have them).
10. **Notify the IRS** if you suspect tax fraud (Form 14039).

### Week 1
11. **Document everything** — every call, email, and dispute. Create a folder. Identity-theft resolution often takes months and good documentation makes a real difference.
12. **Consider a 7-year extended fraud alert** if you have an identity-theft affidavit.
13. **Review specialty reports**: ChexSystems (for bank-account fraud), LexisNexis (for insurance and other), the major credit bureau reports.

## Specific scenarios

### Lost or stolen wallet

- Cancel cards and get replacements
- Get a new driver's license
- File a police report (creates a paper trail in case stolen identity is used)
- Place a fraud alert with one credit bureau

### Phone "SIM-swap" attack

- Call your phone carrier immediately and have them lock your account
- Change passwords on accounts that use SMS 2FA
- Switch SMS 2FA to authenticator-app 2FA where possible

### Tax-return fraud (someone files a return in your name)

- File Form 14039 with the IRS
- The IRS issues a 6-digit IP PIN you use on future returns
- Resolution typically takes 6+ months

### Medical identity theft (someone uses your insurance)

- Request a list of benefits paid from your insurer
- Notify the insurer in writing of the unauthorized use
- Correct your medical records (this is the long part — incorrect medical history can affect future care)

## Common failure patterns

- **Treating identity-theft insurance as the primary defense.** It pays for cleanup costs but does not prevent theft.
- **Reusing passwords.** A password breach at one site cascades.
- **SMS-only 2FA on the most valuable accounts.** SIM-swap defeats it.
- **Ignoring email security.** Email is the key to everything.
- **No credit freezes.** The single highest-leverage defense, available free.
- **Delaying disputes.** Earlier reporting limits liability; later reporting can leave you liable.

## A starter security checklist

- [ ] Credit freezes at Equifax, Experian, TransUnion
- [ ] Unique random passwords for every account, stored in a password manager
- [ ] 2FA via authenticator app on email, banks, brokerages, password manager
- [ ] Account-level alerts on every card and bank account
- [ ] Free credit monitoring through one or more cards or bureaus
- [ ] Annual review of credit reports from all three bureaus
- [ ] Knowledge of where IdentityTheft.gov is and what Form 14039 does

## Further Reading

- [PersonalFinanceGuide](PersonalFinanceGuide) — Where security fits in the broader plan
- [CreditScoreOptimization](CreditScoreOptimization) — Healthy credit habits as a side benefit
- [FinancialResilience](FinancialResilience) — Identity protection as a resilience layer
- [InsuranceTypesAndCoverage](InsuranceTypesAndCoverage) — When identity-theft insurance is and is not duplicative
- [PersonalFinance Hub](PersonalFinanceHub) — Cluster index

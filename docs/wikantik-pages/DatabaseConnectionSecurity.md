---
date: '2026-04-26'
summary: How to secure database connections — TLS, authentication, network controls,
  connection pooling, and the practices that prevent the most common database compromises.
cluster: databases
related:
- DatabaseBackupStrategies
- NetworkSecurityFundamentals
- CloudSecurityFundamentals
- VulnerabilityManagement
canonical_id: 01KQ0P44PC3WFHNSHMN0HVBRDA
type: article
title: Database Connection Security
status: active
tags:
- database-security
- tls
- authentication
- connection-pooling
- security
hubs:
- DatabasePerformanceMonitoringHub
---
# Database Connection Security

Database connections are a high-value target. Compromise the database, compromise everything. Most data breaches involve databases.

Connection security is one layer (the others: encryption at rest, access controls, vulnerability management). This page covers the connection layer.

## The threat model

What you're defending against:

### Network sniffing

Plaintext traffic between application and database. TLS prevents.

### Credential theft

Database password in code, config, or environment. Use secrets management.

### Compromised application

Attacker gets into app; uses its credentials to query database. Limit what the app credential can do.

### Misconfigured access

Database accessible from internet. Network controls prevent.

### Insider threat

Employee with legitimate access does something inappropriate. Auditing detects.

## Defensive layers

### Network controls

Database in private subnet. No internet access. Only specific application servers can connect.

In AWS:
- Database in private subnet
- Security group: only app SG can reach DB port
- No public IP

In Kubernetes:
- Network policies restrict pod-to-pod traffic
- Database pods only accept from specific pods

### TLS

All connections encrypted. PostgreSQL, MySQL, MongoDB all support TLS.

Modern best practice:
- TLS 1.2+
- Server cert validated by client
- Client cert (mTLS) for additional auth

For self-managed: certificates issued by your private CA or Let's Encrypt.

For managed (RDS, Aurora, Cloud SQL): TLS optional but should be required. Set `rds.force_ssl=1` (PostgreSQL) or equivalent.

### Authentication

Database authenticates the connector.

Options:
- **Password**: classic; rotate regularly
- **IAM-based** (RDS supports): tokens generated from cloud credentials
- **Client cert (mTLS)**: cryptographic auth
- **Kerberos**: enterprise-grade
- **SCRAM**: stronger password mechanism (PostgreSQL default in modern versions)

For cloud databases, IAM-based auth eliminates static passwords for application code.

### Authorization

Once authenticated, what can the user do?

- **Least privilege**: app user has only needed permissions. Not superuser.
- **Specific schemas**: app user limited to its schema
- **Specific operations**: SELECT-only for read-only services

Don't share one superuser account across many services.

### Auditing

Log who connected, when, what queries.

- PostgreSQL: pg_audit extension
- MySQL: audit log plugin
- Cloud-managed: enabled via parameter group / configuration

Logs to SIEM for analysis. Compliance often requires this.

## Connection pooling

### Why pool

Establishing connections is expensive. Pools reuse connections across requests.

Without pooling: every HTTP request establishes a new connection. Slow; resource-intensive; database connection limits hit.

### How pools work

Pool maintains N persistent connections. Application borrows; uses; returns. Connection ready for next request.

### Sizing

Too few: requests wait for connection.
Too many: database overloaded; resources wasted.

Common: 10-20 connections per app instance. Tune based on load testing.

### Tools

- **HikariCP** (Java): the gold standard
- **PgBouncer** (PostgreSQL): standalone pooler
- **RDS Proxy** (AWS): managed pooler
- **Application-framework built-in**: Spring's connection management, etc.

For Lambda + RDS: RDS Proxy is essentially required (Lambda concurrency hits connection limits).

## Specific patterns

### Per-service credentials

Each service has its own DB user with its own permissions. Compromise of one service doesn't grant full DB access.

### Read-only replica connections

Read-only services connect to replicas with read-only credentials. Limits blast radius even further.

### Credential rotation

Periodically rotate passwords. Automated:
- Generate new password
- Update database
- Update secrets
- Application picks up new credential
- Old password invalidated

Cloud secret managers support automatic rotation.

### IAM-based auth (cloud)

For RDS:
```python
token = rds.generate_db_auth_token(...)
# Use token as password
```

Token expires after 15 minutes. No long-lived password.

### Bastion hosts vs. session manager

For human DBA access:
- Old: bastion host (jump server)
- Modern: AWS SSM Session Manager (no SSH; through AWS API)

Sessions logged for audit.

### Query whitelisting

For high-security: only specific queries allowed. Prepared statements only; reject ad-hoc queries.

Limits damage from compromised application.

## Hardening checklist

For a production database:

- [ ] Database in private subnet (no public IP)
- [ ] Security group: only app servers can connect
- [ ] TLS required for all connections
- [ ] Strong authentication (SCRAM, IAM, or mTLS)
- [ ] Per-service credentials with least privilege
- [ ] No shared superuser for applications
- [ ] Connection pooling
- [ ] Encrypted at rest (covered separately)
- [ ] Auditing enabled
- [ ] Credential rotation
- [ ] Connection limit alerts (early warning of leaks/attacks)

## Common attacks

### SQL injection

Application doesn't parameterize queries; attacker crafts input that becomes SQL.

Defense: prepared statements. Parameterized queries. Always.

### Credential exposure

Password in code; in environment variable visible to processes; in logs.

Defense: secrets manager; never commit; redact from logs.

### Privilege escalation

App user with too much permission. Attacker escalates within the database.

Defense: least privilege; specific roles per use case.

### Direct database access

Attacker bypasses application; connects directly.

Defense: network controls; no direct database access from internet.

### Backup theft

Backups stolen; restored elsewhere; data exposed.

Defense: encrypt backups; manage backup access carefully.

## Common failure patterns

- **Database public IP.** Inevitably scanned; sometimes compromised.
- **Plaintext connections.** Sniffable.
- **Shared superuser.** Compromise of one app = compromise of all.
- **No connection pooling.** Connection exhaustion under load.
- **Static passwords forever.** No rotation.
- **No auditing.** No detection of suspicious activity.

## Further Reading

- [DatabaseBackupStrategies](DatabaseBackupStrategies) — Adjacent practice
- [NetworkSecurityFundamentals](NetworkSecurityFundamentals) — Network layer
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Cloud-specific
- [VulnerabilityManagement](VulnerabilityManagement) — DB vulnerabilities

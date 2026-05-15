---
applies_to:
- remote-host
verified_at: '2026-05-13T06:15:31.620264701Z'
tags:
- docker
- administration
- self-hosting
- networking
- devops
- open-source
verified_by: gemini-cli-mcp-client
related_to:
- docker-compose.yml
- Git
- Docker Compose
- CapRover
- Nginx Proxy Manager
- Docker Contexts
- SSH
- remote-host
produces:
- remote-host
precedes:
- docker compose up -d
- docker context use remote-host
type: article
uses:
- docker
- /etc/docker/daemon.json
- remote-host
date: 2026-05-13T00:00:00Z
contrasts_with:
- Portainer
title: Docker Administration for Small Networks
alternative_to:
- S3 bucket
- Nginx Proxy Manager
- docker run
- latest
- Docker Compose
cluster: wikantik-development
canonical_id: 01KRFZKPBVD3PJVEY9C9HHQ3PR
summary: Practical strategies and tooling options for managing 6-10 containers across
  a small network, balancing operational simplicity with production reliability.
status: active
---

# Docker Administration for Small Networks

Managing a small fleet of 6-10 containers across 2-3 hosts represents a "Goldilocks" zone in system administration: too complex for manual ad-hoc commands, but small enough that heavy orchestrators like Kubernetes introduce more problems (overhead, complexity, resource drain) than they solve.

This guide outlines a tiered approach to Docker administration, prioritizing simplicity, data integrity, and a "clean" production environment.

## Tier 1: The "Invisible" Minimalist (SSH + Docker Compose)

For users who want zero "middleman" overhead and full control over every configuration byte, the combination of **SSH** and **Docker Compose** remains the gold standard.

### Core Practices
*   **Version Control Everything:** Never run `docker run` directly. Keep every service in a `docker-compose.yml` file stored in a private Git repository.
*   **Infrastructure as Code (Lite):** Use a consistent directory structure on your hosts (e.g., `/opt/stacks/app-name/`).
*   **The "One-File" Rule:** Keep environment variables in `.env` files sibling to your compose files.

### Automation via SSH
You can manage remote hosts without logging in by using the Docker Context feature:
```bash
# Register a remote host
docker context create remote-host --docker "host=ssh://user@host-ip"
# Switch to it
docker context use remote-host
# Run commands as if local
docker compose up -d
```

---

## Tier 2: The Modern PaaS (All-in-One Management)

If you prefer a "Heroku-like" experience where SSL, domain routing, and deployments are handled automatically, several open-source tools have matured to solve this specifically for small networks.

### 1. Coolify (The Feature King)
Coolify is arguably the most advanced self-hosted PaaS. It manages your servers, handles Git-push-to-deploy, and automates database backups.
*   **Best For:** Developers who want a professional cloud experience (Vercel/Railway) on their own hardware.
*   **Resource Note:** Requires at least 2GB of RAM on the management host.

### 2. Dockge (The Compose Specialist)
Created by the developer of Uptime Kuma, Dockge provides a beautiful, reactive UI for managing your Compose stacks. Unlike Portainer, it doesn't try to abstract Docker; it just helps you manage the `.yaml` files.
*   **Best For:** Users who love Docker Compose but want a visual dashboard to see logs and edit files without an editor.

### 3. CapRover (The Rock Solid)
An older, extremely stable PaaS that uses Docker Swarm under the hood for "one-click" apps and automatic Nginx/SSL setup.
*   **Best For:** Set-and-forget stability.

---

## Tier 3: Multi-Host Strategy (The 3-Host Network)

When moving beyond a single host, you must solve for **Networking** and **Storage**.

### 1. Networking: Docker Swarm
For 2-3 hosts, **Docker Swarm** is significantly easier than Kubernetes. It is built into Docker and uses an "overlay network" that allows containers on Host A to talk to Host B as if they were local.
*   **Command:** `docker swarm init` on Host A, `docker swarm join` on Host B/C.

### 2. Reverse Proxy: Traefik or Nginx Proxy Manager
*   **Traefik:** The "native" choice. It automatically detects new containers and generates SSL certificates based on Docker labels.
*   **Nginx Proxy Manager:** Provides a simple web UI to map domains to container IPs.

---

## Essential Production Practices

Regardless of the tool you choose, these three rules prevent "screwing things up":

### 1. The 3-2-1 Backup Rule
Containers are ephemeral; volumes are not. 
*   **Tool:** Use **Restic** or **BorgBackup**.
*   **Strategy:** Backup the `/var/lib/docker/volumes/` (or your bind mounts) and your `.env`/`docker-compose.yml` files daily. Push encrypted copies to an offsite S3 bucket (Backblaze B2 or MinIO).

### 2. Automated Updates (with Caution)
Use **Watchtower** to keep your images fresh.
*   **Pro-Tip:** Use the `:version-tag` (e.g., `postgres:15`) instead of `:latest` to ensure a minor update doesn't break your database schema unexpectedly.

### 3. Log Hygiene
By default, Docker logs can grow indefinitely. Limit them in your `/etc/docker/daemon.json`:
```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
```

## Summary Comparison

| Goal | Recommended Stack |
| :--- | :--- |
| **Max Control / Zero Bloat** | SSH + Git + Docker Contexts |
| **Easy Web Management** | Dockge + Nginx Proxy Manager |
| **Full Private Cloud (GitOps)** | Coolify |
| **High Stability / Multi-Host** | Docker Swarm + CapRover |

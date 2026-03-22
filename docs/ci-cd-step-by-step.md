# CI/CD Setup: Step-by-Step Guide

This guide covers setting up the GitHub Actions CI/CD pipeline for Wikantik using a self-hosted runner on a dedicated build machine. No cloud registries, no external dependencies beyond GitHub itself.

## Architecture

Three machines are involved:

| Machine | Role | What it needs |
|---------|------|---------------|
| **Dev machine** | Write code, push to GitHub | Git |
| **Build machine** | Self-hosted GitHub Actions runner | JDK 21, Maven, Docker, SSH |
| **Production server** | Runs the wiki containers | Docker, repo clone for compose files |

```
You push to main
        │
        ▼
GitHub sends job to your build machine (self-hosted runner)
   ├── 1. Checkout code
   ├── 2. Run unit tests (mvn test)
   ├── 3. Build Docker image (multi-stage Maven → Tomcat)
   ├── 4. Transfer image to prod: docker save | ssh | docker load
   └── 5. SSH to prod: docker compose up -d --no-deps wikantik
                │
                ▼
        Production server loads the image and restarts (~15 sec)
```

No registry. No cloud image storage. The built image goes directly from the build machine to production over SSH.

## What's already in the repo

- `.github/workflows/ci-cd.yml` — the pipeline definition (triggers on push to `main`)
- `Dockerfile` — multi-stage build: Maven 3.9/JDK 21 builds the WAR, Tomcat 11/JDK 21 runs it
- `docker-compose.yml` — references `image: wikantik:latest` (uses pre-built images from CI/CD)
- `docker-compose.prod.yml` — production overrides with resource limits, backup service, and a `build: .` fallback for before CI/CD is set up
- `docker/` — entrypoint, Tomcat config, PostgreSQL init SQL, backup scripts

## Build machine setup

### 1. Install required software

```bash
# JDK 21 (for running Maven tests — faster than building tests inside Docker)
sudo apt install temurin-21-jdk   # or: sdk install java 21-tem

# Maven 3.9+
sudo apt install maven   # or: sdk install maven

# Docker Engine
# Follow https://docs.docker.com/engine/install/ubuntu/
sudo apt install docker-ce docker-ce-cli containerd.io
sudo usermod -aG docker $USER   # runner user needs Docker without sudo
# Log out and back in for group change to take effect

# SSH client (already present on Linux)
```

### 2. Install the GitHub Actions runner

Go to your repo on GitHub: **Settings → Actions → Runners → New self-hosted runner**. GitHub shows the exact commands:

```bash
# Create a directory for the runner
mkdir ~/actions-runner && cd ~/actions-runner

# Download (GitHub gives you the exact URL and hash)
curl -o actions-runner-linux-x64-2.XXX.X.tar.gz -L https://github.com/actions/runner/releases/download/vX.X.X/actions-runner-linux-x64-2.XXX.X.tar.gz
tar xzf actions-runner-linux-x64-2.XXX.X.tar.gz

# Configure — GitHub gives you a one-time token
./config.sh --url https://github.com/jakefearsd/wikantik --token ABCDEF123456

# Install as a systemd service so it survives reboots
sudo ./svc.sh install
sudo ./svc.sh start
```

After this, the runner shows as "Online" in GitHub → Settings → Actions → Runners.

### 3. Generate the deploy SSH key

This key lets the build machine SSH to production to transfer the image and restart the container:

```bash
# Generate a key with no passphrase (the runner runs non-interactively)
ssh-keygen -t ed25519 -f ~/.ssh/wikantik-deploy -N "" -C "wikantik-ci-deploy"

# The private key stays here: ~/.ssh/wikantik-deploy
# The public key goes to the production server (next section)
```

### Disk space management

Each Docker build creates image layers. The workflow has a cleanup step (`docker image prune -f --filter "until=168h"`) that removes images older than 7 days. Maven caches dependencies in `~/.m2/repository` (typically 1-2 GB). The `actions/setup-java` action handles caching across runs.

## Production server setup

This machine only runs containers — it never builds anything.

### 1. Install Docker

```bash
# Docker Engine + Compose plugin
sudo apt install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER
# Log out and back in for group change to take effect
```

### 2. Allow the build machine to SSH in

```bash
# Add the build machine's public key (copy from build machine's ~/.ssh/wikantik-deploy.pub)
echo "ssh-ed25519 AAAA... wikantik-ci-deploy" >> ~/.ssh/authorized_keys
```

Test from the build machine:
```bash
ssh -i ~/.ssh/wikantik-deploy user@prod-host "echo ok"
```

### 3. Clone the repo (for compose files and config only)

```bash
git clone https://github.com/jakefearsd/wikantik.git ~/wikantik
cd ~/wikantik

# Create .env with production secrets
cp .env.example .env
# Edit: set POSTGRES_PASSWORD, WIKANTIK_BASE_URL, SMTP, MCP keys, etc.

# Create the backups directory
mkdir -p backups
```

The repo clone gives Docker Compose the `docker-compose.yml`, `docker-compose.prod.yml`, `docker/` config directory, and `.env`. The application code is never built here — it arrives as a pre-built Docker image.

### 4. First-time startup

Before CI/CD delivers the first image, build locally using the fallback:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Once CI/CD is running, the pipeline replaces the locally-built image with its own via `docker save | ssh | docker load`, and subsequent `docker compose up -d --no-deps wikantik` uses the pipeline's image.

## GitHub repository secrets

Configure these in **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Example value | Purpose |
|--------|--------------|---------|
| `DEPLOY_HOST` | `192.168.1.50` or `wiki.jakefear.com` | Production server address |
| `DEPLOY_USER` | `jakefear` | SSH username on production |
| `DEPLOY_DIR` | `/home/jakefear/wikantik` | Path to the repo clone on production |

`GITHUB_TOKEN` is provided automatically — no setup needed.

## What happens when you push

```
1. You:  git push origin main

2. GitHub:  receives the push, finds ci-cd.yml, sees "runs-on: self-hosted"
            sends the job to your registered runner

3. Build machine (runner):
   a. Checks out the code from GitHub
   b. Sets up JDK 21 (cached after first run)
   c. Runs: mvn clean test -T 1C -B          (~90 seconds)
   d. Runs: docker build -t wikantik:latest . (~3-5 minutes first time,
                                                ~30-60s with layer cache)
   e. Runs: docker save wikantik:latest | gzip | ssh prod 'gunzip | docker load'
            (streams ~60-80MB compressed over SSH)
   f. Runs: ssh prod "cd /path && docker compose up -d --no-deps wikantik"
   g. Waits 30s, then: ssh prod "curl -fsS http://localhost:8080/wiki/Main"
   h. Prunes old Docker images

4. Production server:
   - Receives the image via stdin (docker load)
   - Restarts the wikantik container with the new image (~10-15s downtime)
   - Cloudflare serves cached content during the gap

5. GitHub:  shows green checkmark (or red X if any step failed)
```

Total time: ~5-8 minutes. You see the result in the Actions tab on GitHub.

## Troubleshooting

### Tests fail (step c)
Pipeline stops. Nothing is deployed. You see the failure in GitHub Actions with full Maven output.

### Docker build fails (step d)
Same — stops before deployment. Check the build logs in the Actions tab.

### SSH connection fails (step e/f)
- Check that the deploy key exists at `~/.ssh/wikantik-deploy` on the build machine
- Check that the public key is in `~/.ssh/authorized_keys` on production
- Check firewall rules between the two machines
- Test manually: `ssh -i ~/.ssh/wikantik-deploy user@prod-host "echo ok"`

### Health check fails (step g)
The container is already running with the new image. Check logs on production:
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs wikantik
```
The warning appears in GitHub Actions output but doesn't roll back automatically.

### Runner goes offline
Jobs queue in GitHub until the runner comes back. Check systemd:
```bash
sudo systemctl status actions.runner.*.service
sudo systemctl restart actions.runner.*.service
```

## Rollback

If a deployment breaks the wiki:

```bash
# Option A: revert the commit and push (CI/CD redeploys the fix)
git revert HEAD
git push

# Option B: manually rebuild from a known-good commit on the build machine
cd ~/actions-runner/_work/wikantik/wikantik
git checkout <good-commit-sha>
docker build -t wikantik:latest .
docker save wikantik:latest | gzip | \
  ssh -i ~/.ssh/wikantik-deploy user@prod 'gunzip | docker load'
ssh -i ~/.ssh/wikantik-deploy user@prod \
  "cd /path && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik"
```

## Cost

Self-hosted runners don't consume GitHub Actions minutes — they're unlimited and free. You're only using your own hardware and electricity. The private repo's 2,000 monthly minutes are for GitHub-hosted runners, which you're not using.

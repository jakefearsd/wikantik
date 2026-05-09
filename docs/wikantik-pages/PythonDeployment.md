---
cluster: devops-sre
canonical_id: 01KQ0P44V1YAS7DGGV65353ARZ
title: Python Deployment
type: article
tags:
- python
- gunicorn
- uvicorn
- docker
date: 2025-05-15
summary: A technical guide to production Python deployment, focusing on WSGI/ASGI server configurations and multi-stage Docker optimization.
auto-generated: false
---

# Python Deployment: Production Servers and Dockerization

Deploying Python applications in production requires moving beyond the built-in development servers to a robust stack capable of handling concurrency, process management, and isolation. This article focuses on **Gunicorn/Uvicorn** configurations and **Multi-stage Docker** builds.

## I. The Production Stack: WSGI and ASGI

Python web applications follow either the WSGI (Synchronous) or ASGI (Asynchronous) specification.

### A. WSGI with Gunicorn (Synchronous)
For Django or Flask applications, Gunicorn (Green Unicorn) is the standard.
*   **Worker Model:** Uses a "Pre-fork" worker model. The master process forks several worker processes to handle requests.
*   **Configuration:**
    ```bash
    gunicorn --workers 4 --bind 0.0.0.0:8000 myapp.wsgi:application
    ```
*   **Optimal Worker Count:** A common heuristic is $(2 \times \text{CPUs}) + 1$.

### B. ASGI with Uvicorn (Asynchronous)
For FastAPI or Starlette, Uvicorn provides an implementation of the ASGI spec based on `uvloop` and `httptools`.
*   **Running Uvicorn:**
    ```bash
    uvicorn myapp.main:app --host 0.0.0.0 --port 8000 --workers 4
    ```
*   **The Gunicorn-Uvicorn Combo:** For the best of both worlds (process management + async performance), use Gunicorn as the process manager with Uvicorn workers:
    ```bash
    gunicorn -w 4 -k uvicorn.workers.UvicornWorker myapp.main:app
    ```

## II. Multi-Stage Docker Builds

To minimize image size and improve security, Python applications should use multi-stage Dockerfiles.

### A. The Multi-Stage Strategy
1.  **Builder Stage:** Install build-time dependencies (e.g., `gcc`, `libc-dev`, `python3-dev`) and compile wheels.
2.  **Final Stage:** Copy only the necessary runtime files and the installed packages.

### B. Example Dockerfile
```dockerfile
# Stage 1: Build
FROM python:3.11-slim as builder
WORKDIR /build
RUN apt-get update && apt-get install -y gcc libc-dev
COPY requirements.txt .
RUN pip install --user --no-cache-dir -r requirements.txt

# Stage 2: Runtime
FROM python:3.11-slim
WORKDIR /app
# Copy installed packages from builder
COPY --from=builder /root/.local /root/.local
COPY . .
# Update PATH to include .local/bin
ENV PATH=/root/.local/bin:$PATH
EXPOSE 8000
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "myapp.wsgi:application"]
```

## III. Production Hardening

1.  **Non-Root User:** Never run the application as `root`. Create a dedicated user in the Dockerfile.
2.  **Signal Handling:** Ensure the application correctly handles `SIGTERM` for graceful shutdowns.
3.  **Logging:** Log to `stdout/stderr` so the container orchestrator (Kubernetes/ECS) can capture logs.
4.  **Resource Limits:** Define CPU and Memory limits in the orchestrator to prevent a single container from starving the host.

## IV. Conclusion: Deployment as Code

Production Python deployment is a balance between performance (Uvicorn), stability (Gunicorn), and efficiency (Multi-stage Docker). By automating this stack through CI/CD pipelines, you ensure that every deployment is repeatable, secure, and performant.

For further optimization, see [SiteReliabilityEngineering](SiteReliabilityEngineering) and [SecretsManagement](SecretsManagement).

---
cluster: data-engineering
canonical_id: 01KQ0P44PBB2AZAQWSHSN97JZF
title: Data Versioning
type: article
tags:
- data-engineering
- dvc
- git-lfs
- reproducibility
- mlops
status: active
date: 2025-05-15
summary: Technical guide to versioning large datasets and model artifacts. Covers DVC (Data Version Control) mechanics and integration with Git.
auto-generated: false
---

# Data Versioning: Reproducibility at Scale

In data science and ML, code versioning (Git) is insufficient. You must also version the **Data** and the **Model Artifacts** to ensure reproducibility.

## 1. DVC (Data Version Control) Mechanics

DVC acts as a bridge between Git and a large-file remote (S3, GCS, local server).
*   **Pointer Files:** When you run `dvc add data.csv`, DVC creates a `data.csv.dvc` file (a few bytes) containing the file's hash (MD5/SHA256).
*   **Git Integration:** You commit the `.dvc` pointer to Git. Git tracks the *version* of the pointer, while the actual 10GB file is pushed to the DVC remote.
*   **Reconstruction:** A collaborator runs `git pull` followed by `dvc pull`. DVC reads the hash from the pointer file and downloads the exact matching version of the data from the remote.

## 2. Pipeline Management (`dvc.yaml`)

DVC allows you to define data pipelines as Directed Acyclic Graphs (DAGs).
*   **Stages:** Define a command, its dependencies (code/data), and its outputs.
*   **Concrete Benefit:** If you change the training script but not the preprocessing script, `dvc repro` will skip the preprocessing stage and only run the training, saving significant computation time.

## 3. Alternative: Git LFS (Large File Storage)

Git LFS is a standard extension for tracking large files within Git.
*   **Pros:** Native integration with GitHub/GitLab.
*   **Cons:** Less flexible for data-specific workflows (pipelines, cloud-agnostic remotes). 
*   **Expert Choice:** Use Git LFS for binary assets (images, fonts). Use DVC for research datasets and ML models.

## 4. Versioning Databases: Liquibase/Flyway

For relational data, versioning means managing the **Schema Evolution**.
*   **Migration Scripts:** Code-based definitions of changes (e.g., `20240515_add_customer_id_to_orders.sql`).
*   **Rollback:** Every migration must include a rollback script to revert changes if the deployment fails.

---
**See Also:**
- [Data Lakehouse](DataLakehouse) — Time travel on object storage.
- [Infrastructure As Code](InfrastructureAsCode) — Versioning the environment.
- [Backwards Compatibility Strategies](BackwardsCompatibilityStrategies) — Managing schema drift.

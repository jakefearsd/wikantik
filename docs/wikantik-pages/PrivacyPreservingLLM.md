---
title: Privacy Preserving Llm
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- privacy
- differential-privacy
- federated-learning
- confidential-computing
- llm
summary: Techniques for using LLMs without exposing data — confidential
  computing, on-prem deployment, differential privacy, federated approaches —
  and the realistic threat models each addresses.
related:
- AiDataPrivacyAndCompliance
- LocalRAG
- OpenSourceLLMs
- ResponsibleAiDeployment
hubs:
- AgenticAi Hub
---
# Privacy-Preserving LLM

Many use cases want LLM capability without sending data to a third-party API. The mitigations span deployment choices (where the model runs), training-time privacy (differential privacy), and inference-time techniques (private retrieval, federated approaches).

This page is the working set, ranked by how often each is the right answer.

## What threat are you defending against?

Before picking techniques, articulate the threat:

- **Model provider seeing data.** Anthropic / OpenAI logging your prompts. Mitigation: provider's enterprise tier (logging off by contract) or self-hosted.
- **Provider's training corpus contamination.** Your data ending up in next year's training data. Mitigation: opt-out (default in enterprise) or self-hosted.
- **Model leaking training data.** A model trained on customer data later regurgitating it. Mitigation: differential privacy in training; don't train on sensitive data.
- **Inference-time data leakage.** Logs of prompts and outputs accessible by attackers / employees. Mitigation: redaction at logging; access controls.
- **Cross-tenant leakage.** User A sees data from User B's interactions. Mitigation: tenant isolation in retrieval and prompt construction.
- **Adversarial data extraction.** Attacker uses prompts to extract memorised data. Mitigation: training-time DP; runtime guardrails.

Each of these has different mitigations. Pick based on which threats matter for your application.

## Self-hosting (the simplest answer)

If "data must not leave our infrastructure" is the constraint, run the model yourself.

- **Open-weights LLM** (Llama, Qwen, Mistral). See [OpenSourceLLMs].
- **Run on hardware you control** (cloud VPC, on-prem, customer-deployed).
- **Logs and prompts stay in your perimeter.**
- **Encryption at rest and in transit** as you'd do for any sensitive data.

This is the answer for most "we can't send data to OpenAI" cases. Quality trades off vs commercial frontier; operational cost is real but bounded.

See [LocalRAG] for the RAG variant of this story.

## Confidential computing

Cloud providers offer confidential VMs / containers (AWS Nitro Enclaves, GCP Confidential VMs, Azure Confidential Computing) that encrypt memory, prevent host-OS introspection, and provide attestation that you're running on a verified configuration.

Use case: you want to use a third-party model (or third-party hardware) without trusting them with cleartext data.

- **Model is in the enclave; data is decrypted only inside the enclave; output re-encrypted on exit.**
- Provider can't see your data even with full host access.
- Performance overhead: 5-30% depending on workload.

For genuinely-sensitive workloads where self-hosting isn't feasible (specialised hardware, model size), confidential compute is the answer. Maturity is increasing rapidly through 2026.

Caveat: cryptographic protection against the host doesn't protect against your own application bugs. The enclave is a strong perimeter; what runs inside is your responsibility.

## Customer-deployed model serving

Anthropic, OpenAI, and Cohere offer "deploy our model in your infrastructure" enterprise tiers:

- They ship the model weights to your cloud account.
- You serve from your own infrastructure.
- Logs stay with you.
- You pay for compute + a license fee.

For organisations that want frontier-quality with self-hosted privacy, this is increasingly available. Cost is higher than API; operational responsibility is higher; data stays in the customer perimeter.

## Differential privacy in training

DP-SGD (differentially private stochastic gradient descent) trains models with mathematical guarantees that no individual training example can be reverse-engineered from the trained model.

Mechanics: clip per-example gradients; add Gaussian noise; sum.

Cost:

- Training is slower (per-example gradient computation).
- Final model quality is worse than non-DP training (typically 2-10 points on benchmarks).
- Privacy budget (`ε`) is a parameter; lower budget = more privacy = worse quality.

Used for:

- Training on sensitive datasets where individual leakage is unacceptable.
- Compliance-driven training (some healthcare data uses).

Not used for: most production fine-tuning on company data, where quality is prioritised.

## Federated learning

Train across multiple parties without centralising the data. Each party trains locally on their data; gradients (or model updates) are aggregated centrally; the central model improves.

Use cases:

- Training on data spread across hospitals (each contributes; no single hospital's data leaves).
- Mobile-device personalisation (each device contributes; raw data stays on device).

For LLMs specifically, federated training is rare in 2026 because pretraining is too compute-intensive to do federated. Federated fine-tuning is more plausible.

The technique works; the use cases are narrower than its enthusiasts claim.

## PII redaction and structured input

Before sending to an LLM, redact or pseudonymise sensitive fields:

```
Original: "Charge John Smith's card 4111-1111-1111-1111 for $50"

Redacted: "Charge {NAME} card {CARD} for ${AMOUNT}"

LLM processes redacted version.

After response, re-substitute.
```

Tools: Microsoft Presidio, AWS Macie, Google DLP API, custom regex for known patterns.

For some workflows this is enough; for others, the redaction itself loses context (you might need to know the name to do the right thing).

Pattern: redact at ingestion when a third party shouldn't see the data; keep the link in your own DB; re-attach after.

## Local-only retrieval

For RAG specifically: keep the corpus local; only retrieved chunks go to the LLM. If you can't avoid sending the LLM the chunks, at least:

- Filter sensitive content before retrieval.
- Apply tenant isolation.
- Don't include raw PII in the chunks where avoidable.
- Audit what's sent.

Combined with self-hosted LLM, the chunks never leave your perimeter at all.

## Output filtering

Treat LLM outputs as untrusted before showing them:

- Detect leaked PII in the output (model may have generated something it shouldn't).
- Detect leaked context (the user shouldn't see another user's data).
- Detect responses that quote training data (prompt injection sometimes elicits memorised content).

Tools: re-running PII detection on outputs; structured-output validation; comparison to expected response shapes.

## The defence-in-depth layering

For sensitive applications:

1. **Data minimisation** — don't send the LLM what it doesn't need.
2. **Redaction** at the boundary.
3. **Tenant isolation** in retrieval.
4. **Self-hosted or confidential-compute** model serving.
5. **No training on customer data** unless explicitly opted in.
6. **Output filtering** for leakage.
7. **Audit logging** with appropriate retention and access controls.
8. **DSAR / deletion** pipelines.

Most production deployments need most of these. Skipping any of them is a specific trade-off you should be able to articulate.

## Compliance overlay

Privacy regulations interact with LLM-specific concerns:

- **GDPR Article 22** — automated decision-making with significant effects requires human review.
- **GDPR data subject rights** — apply to training data, prompts, outputs, derived data.
- **HIPAA** — health-data-specific rules; "HIPAA-eligible" tiers exist for major LLM providers.
- **State / local AI laws** — vary; track ones applicable to your users.

See [AiDataPrivacyAndCompliance].

## Where this is going

Trends through 2026:

- **Hardware-accelerated confidential compute** — H100 / B200 GPUs with confidential-compute features; less performance overhead.
- **Stronger differential privacy methods** — better quality at the same privacy budget.
- **Verifiable inference** — cryptographic proofs that an inference happened correctly without revealing the input. Research-stage; production maturity uncertain.
- **More managed self-hosted options** — vendors offering "we manage the model in your VPC" with clearer responsibility splits.

Privacy-preserving LLM is an evolving space. The 2026 set of techniques will look different in 2028.

## Practical recommendations by use case

| Use case | Privacy approach |
|---|---|
| Internal productivity, low-sensitivity | Commercial API with enterprise tier |
| Internal data, moderate-sensitivity | Self-hosted on company infrastructure |
| Customer data, with their consent | Self-hosted; redaction; tenant isolation |
| Healthcare / finance regulated | Self-hosted or HIPAA-eligible API; full audit |
| Multi-tenant SaaS | Tenant-isolated self-hosted; or strict commercial enterprise tier |
| Cross-organisation data | Confidential compute or federated approach |
| Edge / on-device | Small open model on the device; nothing leaves |

For most teams, "self-hosted open model" is the answer to "we have privacy concerns and can't use the commercial API." The frontier-quality gap is the cost.

## Further reading

- [AiDataPrivacyAndCompliance] — regulatory specifics
- [LocalRAG] — local-only retrieval pipeline
- [OpenSourceLLMs] — what to self-host
- [ResponsibleAiDeployment] — broader responsible-AI context

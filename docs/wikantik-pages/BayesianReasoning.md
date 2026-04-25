---
canonical_id: 01KQ12YDSM0FJVYR50JQBGT844
title: Bayesian Reasoning
type: article
cluster: mathematics
status: active
date: '2026-04-25'
tags:
- bayesian
- probability
- inference
- mcmc
- statistics
summary: Bayesian inference for engineers — the rule that doesn't lie, the priors
  question that does, and when you reach for variational methods, MCMC, or
  conjugate-prior cheats.
related:
- ProbabilityTheory
- MarkovChainFundamentals
- LinearAlgebra
- ClusteringAlgorithms
- BayesianHyperparameterTuning
hubs:
- Mathematics Hub
---
# Bayesian Reasoning

Bayes' rule is unimpressive on a chalkboard and devastating in practice. It tells you how to update beliefs as evidence arrives. The hard parts are: (1) being honest about your prior, and (2) doing the integration when the prior and likelihood don't have a nice closed form.

The first is a discipline problem. The second is a computational one.

## The rule

```
P(H | E) = P(E | H) * P(H) / P(E)

posterior = likelihood × prior / evidence
```

In words: the probability of hypothesis `H` given evidence `E` equals the probability of `E` given `H`, times your prior belief in `H`, normalised by the total probability of `E`.

That's it. Every Bayesian computation is some flavour of this — sometimes one-shot, sometimes iterated, sometimes approximated, sometimes integrated over high-dimensional parameter spaces.

## A worked example: spam classification

You receive an email containing the word "viagra." Is it spam?

Prior: 30% of all email is spam. `P(spam) = 0.3`.

Likelihood: 85% of spam contains "viagra"; 0.5% of non-spam contains it. `P(viagra | spam) = 0.85`, `P(viagra | not spam) = 0.005`.

Evidence: `P(viagra) = P(viagra | spam) P(spam) + P(viagra | not spam) P(not spam) = 0.85 × 0.3 + 0.005 × 0.7 = 0.2585`.

Posterior: `P(spam | viagra) = 0.85 × 0.3 / 0.2585 ≈ 0.987`.

98.7% spam. Notice how the prior (30%) gets dramatically updated by a single discriminative feature — that's Bayes doing its job.

The same machinery applies to medical testing (P(disease | positive test)), retrieval (P(relevant | query+doc features)), and pretty much any "given evidence E, how confident am I in H" question.

## Why people get Bayes wrong

The classic medical-test trap: a test is 99% accurate. Your test came back positive. Probability you're sick?

If only 1 in 1000 has the disease, the math is:

```
P(sick | positive) = (0.99 × 0.001) / (0.99 × 0.001 + 0.01 × 0.999)
                   ≈ 0.09
```

9%, not 99%. The prior dominates when the disease is rare. Most people, including most doctors, get this wrong.

Internalising base-rate neglect is the single most useful thing Bayesian thinking gives you outside of the math.

## Priors: where the discipline lives

Bayesian inference requires a prior. You will be tempted to say "flat prior, no information" — but flat priors are themselves a strong claim (uniform over an unbounded parameter is improper) and often the wrong one.

Categories of priors:

- **Informative priors** — based on previous data or domain knowledge. "We've seen this kind of system before; the parameter is around 0.3 ± 0.1."
- **Weakly informative priors** — wide enough to allow surprises but ruling out absurdities. "Probably between 0 and 1; not 10⁶."
- **Non-informative / flat priors** — uniform over a bounded range. Use only when you genuinely have no information; reach for a weakly informative prior first.
- **Conjugate priors** — chosen for mathematical convenience (closed-form posterior). Beta-Binomial, Normal-Normal, Dirichlet-Multinomial. See below.

Choosing a prior is making a claim. Be transparent about it; do prior predictive checks (sample from the prior, see if predictions are absurd); be willing to revise.

## Conjugate priors: the cheap path

When prior and likelihood are conjugate, the posterior has the same family as the prior — just updated parameters. No integration needed. The classic pairs:

| Likelihood | Conjugate prior | Posterior |
|---|---|---|
| Bernoulli (binary) | Beta(α, β) | Beta(α + successes, β + failures) |
| Binomial | Beta(α, β) | Beta(α + k, β + n - k) |
| Multinomial | Dirichlet(α) | Dirichlet(α + counts) |
| Normal (mean, σ known) | Normal | Normal (analytical update) |
| Normal (mean, σ unknown) | Normal-Inverse-Gamma | Normal-Inverse-Gamma |
| Poisson | Gamma(α, β) | Gamma(α + Σx, β + n) |

If your problem fits one of these, the math is one line and the posterior is exact. Do this when you can.

## When conjugate doesn't apply: approximate inference

Most real problems don't have conjugate priors. The posterior `P(θ | data)` is some intractable integral. You approximate.

### MCMC (Markov Chain Monte Carlo)

Sample from the posterior by constructing a Markov chain whose stationary distribution is the posterior. Most common variants:

- **Metropolis-Hastings** — propose a step, accept with probability proportional to posterior ratio. General, slow.
- **Hamiltonian Monte Carlo (HMC) / NUTS** — uses gradient information to take big informed steps. Far more sample-efficient. The default in modern probabilistic programming (Stan, PyMC, NumPyro).

MCMC gives you actual samples from the posterior. With samples you can compute any quantity (mean, variance, credible intervals).

Cost: thousands to millions of likelihood evaluations. For models with cheap likelihoods, fine; for expensive ones (large neural nets), often impractical.

### Variational inference (VI)

Pick a family of distributions `q(θ; φ)` and find the parameters `φ` that make `q` closest to the true posterior in KL divergence. Optimisation problem rather than sampling problem.

Faster than MCMC; the result is an approximation, not exact samples. Quality depends on whether the variational family is expressive enough — mean-field VI (independent factors) often underestimates posterior variance.

Modern variants (normalizing flows, neural network variational families) are more expressive at higher computational cost. For Bayesian deep learning, VI is usually the only tractable choice.

### Laplace approximation

Approximate the posterior by a Gaussian around its mode. Cheap; adequate for unimodal posteriors with enough data; fails for multimodal or non-Gaussian shapes.

## Bayesian thinking in machine learning

Most modern ML is implicitly Bayesian:

- **Regularisation as a prior.** L2 regularisation is a Gaussian prior on weights. L1 is a Laplace prior. Adding regularisation = encoding "small weights are more likely."
- **Bayesian neural networks.** Posterior over weights, not point estimates. Provides uncertainty estimates; expensive to train; useful in safety-critical contexts.
- **Gaussian processes.** A non-parametric Bayesian regression / classification method. Excellent at small-data regimes with calibrated uncertainty.
- **Probabilistic programming.** Stan, PyMC, NumPyro, Pyro. Lets you write generative models as code and run inference automatically.

For workhorse production ML, you mostly use Bayesian *ideas* (regularisation, priors implicit in data augmentation) rather than full Bayesian inference. Full inference earns its keep when uncertainty quantification or small data are critical.

## Bayesian thinking outside ML

The pattern shows up far beyond probability classes:

- **A/B testing** — Bayesian A/B testing gives "P(B is better | data)" directly. Easier to interpret than frequentist p-values. See [BayesianHyperparameterTuning] for the optimisation analogue.
- **Anomaly detection** — model a baseline distribution, flag low-likelihood events. Bayesian framing is natural.
- **Forecasting** — Bayesian time-series (Prophet's mixture, structural time-series models in TFP) handle uncertainty in projections naturally.
- **Reasoning under uncertainty** — calibrated forecasting, evaluating the strength of evidence, updating beliefs in light of new data. The general practice of explicit base rates and explicit likelihoods is useful even without doing the math.

## When Bayesian is the wrong frame

- **You have effectively infinite data.** Frequentist methods converge to the same answer; the Bayesian framing adds complexity for no benefit.
- **Stakeholders need point predictions.** You can give them the posterior mean, but if no one looks at the uncertainty, you're paying for uncertainty quantification you don't use.
- **Computational budget is tight.** MCMC and VI cost more than maximum-likelihood. If the answer doesn't change much, save the cycles.

The honest Bayesian answer is "Bayes is right; sometimes it's not worth the cost."

## Tools

- **PyMC** — Python; modern; nice API; uses NumPyro/JAX backend.
- **Stan** — most mature; own language; rock-solid HMC.
- **NumPyro** — JAX-based; fast; good for large models.
- **TFP (TensorFlow Probability)** — TF-native.
- **Turing.jl** — Julia; flexible.

For prototyping Bayesian models in 2026, PyMC or NumPyro. Stan if you need maximum reliability or want to read a lot of well-written tutorials.

## Further reading

- [ProbabilityTheory] — the foundation
- [MarkovChainFundamentals] — the basis of MCMC
- [LinearAlgebra] — covariance matrices, conjugate priors
- [ClusteringAlgorithms] — Gaussian mixture models, etc.
- [BayesianHyperparameterTuning] — practical Bayesian optimisation

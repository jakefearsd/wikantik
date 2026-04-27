---
canonical_id: 01KQ0P44TS2QKK143RD755SRYP
title: Probability Theory
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: The mathematics of uncertainty — sample spaces, random variables, distributions,
  expectation, and the foundations underlying statistics, machine learning, and probabilistic
  reasoning.
tags:
- probability
- mathematics
- statistics
- random-variables
related:
- AppliedMathSurvey
- CalculusRefreshForCS
- SetTheoryLogic
- FuzzyLogic
hubs:
- Mathematics Hub
---
# Probability Theory

Probability theory is the mathematics of uncertainty. From the foundations of statistical inference to modern machine learning, probability is the framework for reasoning about the unknown.

This page covers the practical concepts.

## Sample spaces and events

A sample space Ω is the set of all possible outcomes of an experiment.

For a coin flip: Ω = {H, T}.
For a die roll: Ω = {1, 2, 3, 4, 5, 6}.
For "draw two cards": Ω is all pairs of cards.

An event is a subset of the sample space — a collection of outcomes.

## Probability as a function

A probability assigns a number between 0 and 1 to each event:

- P(Ω) = 1 (something happens)
- P(∅) = 0 (impossible event)
- P(A ∪ B) = P(A) + P(B) - P(A ∩ B) (inclusion-exclusion)
- P(¬A) = 1 - P(A)

For disjoint events: P(A ∪ B) = P(A) + P(B).

## Conditional probability

P(A | B) = P(A ∩ B) / P(B)

"Probability of A given B has occurred."

For a die: P(roll = 6 | roll is even) = (1/6) / (3/6) = 1/3.

## Independence

A and B are independent if P(A ∩ B) = P(A) · P(B).

Equivalently, P(A | B) = P(A) — knowing B doesn't change probability of A.

For two coin flips: independent. For "card 1 = ace" and "card 2 = ace" without replacement: not independent.

## Bayes' theorem

P(A | B) = P(B | A) · P(A) / P(B)

Inverts conditional probability. Foundation of Bayesian inference.

For medical testing:
- P(disease) = 0.01 (prior)
- P(positive test | disease) = 0.95 (test sensitivity)
- P(positive test | no disease) = 0.05 (false positive rate)

P(disease | positive test) = (0.95 · 0.01) / (0.95 · 0.01 + 0.05 · 0.99) ≈ 0.16

Even with a "95% accurate" test, positive results aren't very reliable when the prior is low. Counterintuitive but important.

## Random variables

A function from the sample space to the real numbers.

- For die roll: X = the number rolled
- For coin flip: Y = 1 if heads, 0 if tails

Allows applying mathematical operations to outcomes.

## Distributions

Describe how a random variable takes values.

### Discrete distributions

Values are countable.

- **Bernoulli**: success/failure (p)
- **Binomial**: number of successes in n trials
- **Poisson**: count of rare events
- **Geometric**: trials until first success

### Continuous distributions

Values are continuous.

- **Uniform**: equal probability over an interval
- **Normal (Gaussian)**: bell curve; mean μ, variance σ²
- **Exponential**: time between events
- **Beta**: distributions over [0,1]
- **Gamma**: many uses

The normal distribution is special: many things are approximately normal due to the central limit theorem.

## Expectation and variance

### Expectation E[X]

The "average" value:
- Discrete: E[X] = Σ x · P(X = x)
- Continuous: E[X] = ∫ x · f(x) dx

For die roll: E[X] = (1+2+3+4+5+6)/6 = 3.5.

### Variance Var(X)

Average squared deviation from mean: Var(X) = E[(X - E[X])²].

Standard deviation σ = √Var(X).

Captures spread: high variance = wide distribution; low variance = concentrated.

### Properties

- E[aX + b] = aE[X] + b
- Var(aX + b) = a² Var(X)
- E[X + Y] = E[X] + E[Y]
- Var(X + Y) = Var(X) + Var(Y) (if independent)

## Central limit theorem

Sum of many independent random variables is approximately normal, regardless of their individual distributions (under modest conditions).

Implications:
- Sample means are approximately normal
- Statistical inference relies on this
- Many natural phenomena are normal-distributed because they're sums of many small effects

## Specific results

### Law of large numbers

As you take more samples, the sample mean approaches the true mean. With probability 1, in the limit.

Foundation of frequentist statistics.

### Markov's inequality

P(X ≥ a) ≤ E[X] / a for non-negative X and positive a.

Useful for tail-probability bounds.

### Chebyshev's inequality

P(|X - μ| ≥ kσ) ≤ 1/k².

Bounds probability of being far from the mean.

## Joint distributions

For multiple random variables. P(X = x, Y = y).

Conditional: P(X | Y).
Marginal: P(X) = Σ P(X = x, Y = y) over y.

For dependent variables: covariance Cov(X, Y) = E[(X - μ_X)(Y - μ_Y)].

Correlation = covariance normalized.

## Stochastic processes

Random variables indexed by time. Many applications:

- **Markov chains**: state transitions with memoryless property
- **Random walks**: steps in random directions
- **Brownian motion**: continuous-time random process
- **Poisson processes**: random arrivals

## Applications

### Statistics

Estimation, hypothesis testing, regression — all probabilistic frameworks.

### Machine learning

- Bayesian inference
- Probabilistic models (HMM, Bayesian networks)
- Loss functions are often log-probability
- Generative models learn data distributions
- Uncertainty quantification

### Cryptography

Hash functions, random number generation, security proofs all use probability.

### Information theory

Entropy, mutual information, cross-entropy — all probability-theoretic.

### Algorithms

Randomized algorithms (quicksort, hashing, primality testing) use probability for efficiency.

### Risk modeling

Finance, insurance, reliability engineering use probability for risk assessment.

## Common probability paradoxes

### Monty Hall

Three doors; prize behind one. You pick a door; host opens a different door (revealing no prize). Should you switch?

Yes, you should. Counterintuitive but provable: switching wins 2/3, staying wins 1/3.

### Birthday problem

How many people in a room before two share a birthday with >50% probability?

Just 23. Surprisingly few.

### Base rate fallacy

Ignoring prior probability when interpreting test results.

The medical-test example above; base rate dominates the result.

## Common failure patterns

- **Ignoring priors.** Bayesian thinking accounts for prior; many people don't.
- **Confusing correlation with causation.**
- **Misunderstanding independence.** Two events being uncorrelated doesn't mean independent (depending on framework).
- **Selection bias.** Not all data is random; population matters.
- **Naive probabilistic intuitions.** Many results contradict gut feeling.

## Further Reading

- [AppliedMathSurvey](AppliedMathSurvey) — Probability in context
- [CalculusRefreshForCS](CalculusRefreshForCS) — Calculus underlies continuous distributions
- [SetTheoryLogic](SetTheoryLogic) — Probability is over sets
- [FuzzyLogic](FuzzyLogic) — Different framework for uncertainty
- [Mathematics Hub](Mathematics+Hub) — Cluster index

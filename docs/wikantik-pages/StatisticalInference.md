---
title: Statistical Inference
type: article
cluster: mathematics
status: active
date: '2026-05-06'
summary: A rigorous guide to drawing conclusions from data, covering hypothesis testing, p-values, confidence intervals, and the Frequentist vs. Bayesian debate.
tags: [mathematics, statistics, inference, hypothesis-testing, p-values]
related: [StatisticsFundamentals, ProbabilityTheory, RegressionAnalysis, MathematicsHub]
---

# Statistical Inference: Drawing Conclusions from Data

Statistical inference is the process of using data analysis to deduce properties of an underlying probability distribution. It allows us to move from "What do we see in this sample?" to "What can we say about the whole population?"

---

## I. The Frequentist Framework

Frequentist inference is based on the idea that probability is the long-run frequency of repeatable events. It is the standard approach used in most scientific research and A/B testing.

### 1.1 Hypothesis Testing
We start with two competing hypotheses:
*   **Null Hypothesis ($H_0$):** There is no effect or no difference (the "status quo").
*   **Alternative Hypothesis ($H_1$):** There is an effect, a difference, or a relationship.

### 1.2 The p-value
The **p-value** is the probability of observing a result at least as extreme as the one we got, *assuming the null hypothesis is true*.
*   **Small p-value ($\leq 0.05$):** The result is unlikely to have happened by chance under $H_0$. we "reject the null hypothesis."
*   **Large p-value ($> 0.05$):** We "fail to reject the null hypothesis."

**Crucial Warning:** A p-value is NOT the probability that the null hypothesis is true. It is a statement about the data, not the hypothesis itself.

### 1.3 Significance Level ($\alpha$)
The threshold we set *before* the experiment (usually 0.05). If p-value $< \alpha$, the result is "statistically significant."

### 1.4 Errors in Testing
*   **Type I Error (False Positive):** Rejecting $H_0$ when it is actually true. (The probability is $\alpha$).
*   **Type II Error (False Negative):** Failing to reject $H_0$ when $H_1$ is actually true. (The probability is $\beta$).
*   **Statistical Power ($1 - \beta$):** The probability that the test will correctly reject a false null hypothesis. Higher power requires larger sample sizes.

---

## II. Estimation and Confidence Intervals

Instead of just a "Yes/No" on a hypothesis, we often want to estimate the value of a population parameter.

### 2.1 Point Estimates
A single value (e.g., the sample mean) used to estimate the population parameter.

### 2.2 Confidence Intervals (CI)
A range of values that is likely to contain the population parameter. A **95% Confidence Interval** means that if we repeated the experiment 100 times, we would expect the interval to contain the true parameter in 95 of those trials.
*   **Interpretation**: It quantifies our uncertainty. A narrow CI means we have a precise estimate; a wide CI means we need more data.

---

## III. Common Statistical Tests

### 3.1 The t-test
Used to determine if there is a significant difference between the means of two groups.
*   **Real-World Application**: Testing if a new caching algorithm (Group B) results in lower average latency than the current one (Group A).

### 3.2 ANOVA (Analysis of Variance)
Generalizes the t-test to more than two groups.
*   **Real-World Application**: Comparing the performance of a software service across four different cloud providers.

### 3.3 Chi-Square Test
Used for categorical data to see if two variables are independent.
*   **Real-World Application**: Testing if the "Signup" event is independent of the "Landing Page" version.

---

## IV. The Bayesian Alternative

Bayesian inference treats probability as a **degree of belief**. It uses [Bayes' Theorem](ProbabilityTheory#bayes-theorem) to update the probability for a hypothesis as more evidence becomes available.

$$ P(H | D) = \frac{P(D | H) P(H)}{P(D)} $$

*   **Prior $P(H)$**: What we believed before seeing the data.
*   **Likelihood $P(D | H)$**: How likely the data is, given the hypothesis.
*   **Posterior $P(H | D)$**: Our updated belief after seeing the data.

**Key Advantage**: Bayesian inference can incorporate prior knowledge (e.g., "we know from 10 years of experience that the baseline conversion rate is 2%").

---

## V. Real-World Application: A/B Testing

In software products, A/B testing is the practical application of statistical inference.

### 5.1 The Experiment
*   **Control (A)**: The current version of a feature.
*   **Treatment (B)**: The new version.
*   **Metric**: Conversion rate (clicks/views).

### 5.2 The Analysis
We use a t-test or a Bayesian model to determine if the difference in conversion rates is "real" or just noise.
*   **Practical Pitfall: Optional Stopping**. If you keep checking the p-value and stop as soon as it hits 0.05, you are dramatically increasing your Type I error rate. You must decide the sample size *before* starting (Power Analysis).

### 5.3 Practical Significance vs. Statistical Significance
A result can be statistically significant (p < 0.05) but practically useless. If a change increases conversion by 0.0001%, it might be "real," but the cost of implementing it might outweigh the benefit. **Effect Size** matters more than the p-value in business decisions.

---
**See Also:**
- [Statistics Fundamentals](StatisticsFundamentals) — Descriptive statistics.
- [Regression Analysis](RegressionAnalysis) — Predicting outcomes.
- [Probability Theory](ProbabilityTheory) — The formal engine.
- [Bayesian Reasoning](BayesianReasoning) — Deep dive into Bayesian methods.

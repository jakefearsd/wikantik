---
canonical_id: 01KQQ6SGVRSG0BJMX4AKGGF23S
date: 2026-05-03T00:00:00Z
cluster: mathematics
type: article
tags:
- mathematics
- information-theory
- entropy
- shannon
- probability
- coding-theory
title: Information Theory
relations:
- type: part-of
  target_id: 01KQ2P44XMGA8E1E7GAT4AYV43
- type: prerequisite-for
  target_id: 01KQEKGD8QYAS6P09AM61S5E2W
- type: prerequisite-for
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
summary: A foundational survey of Information Theory, founded by Claude Shannon. Covers
  the quantification of information via entropy, the limits of data compression, and
  the fundamental constraints of communication over noisy channels.
status: active
---

# Information Theory

Information Theory is the mathematical study of the quantification, storage, and communication of information. Founded by Claude Shannon in his 1948 paper, "A Mathematical Theory of Communication," it provides the formal foundations for everything from data compression (ZIP, MP3) to reliable deep-space communication and modern machine learning.

## 1. Quantifying Information: Entropy

At the heart of the theory is **Shannon Entropy ($H$)**, which measures the amount of uncertainty or "surprise" in a random variable.

The formula for entropy of a discrete random variable $X$ is:
$$H(X) = -\sum p(x) \log_b p(x)$$
Where $p(x)$ is the probability of outcome $x$. If $b=2$, the unit is the **bit**.

- **Entropy = 0**: The outcome is certain (no information gained by observing it).
- **Maximum Entropy**: All outcomes are equally likely (maximum uncertainty).

### Example: The Biased Coin
- A **fair coin** ($p=0.5$) has $H = 1$ bit of entropy.
- A **biased coin** ($p=0.9$ heads) has $H \approx 0.47$ bits. It is more predictable, so each flip carries less "new" information.

## 2. Mutual Information

Mutual Information ($I(X; Y)$) measures how much information is shared between two variables. It quantifies the reduction in uncertainty about $X$ that results from knowing $Y$.

$$I(X; Y) = H(X) - H(X|Y)$$

In **Machine Learning**, mutual information is used for feature selection (identifying which input features provide the most information about the target label) and for understanding the "information bottleneck" in deep neural networks.

## 3. The Limits of Communication

Shannon's two fundamental theorems define the absolute limits of technology:

### Source Coding Theorem (Compression)
You cannot compress data into fewer bits than its entropy without losing information. Entropy is the fundamental limit of lossless data compression.

### Noisy-Channel Coding Theorem (Capacity)
For any given noisy channel, there exists a **Channel Capacity ($C$)**. As long as the rate of information transmission ($R$) is less than $C$, there exists a coding scheme that allows for communication with an arbitrarily small error rate.

$$C = B \log_2(1 + \frac{S}{N})$$
(Where $B$ is bandwidth, $S$ is signal power, and $N$ is noise power).

## Applications in Computing
- **Computer Science**: Huffman coding, error-correcting codes (ECC memory).
- **Machine Learning**: Cross-entropy loss functions, decision tree splitting (Information Gain).
- **Cryptography**: Measuring the "randomness" of keys.
- **Data Structures**: [[Bloom Filters]], hash function analysis.

## See Also
- [[MathematicsHub]]
- [[ComputerScienceFoundationsHub]]
- [[ProbabilityTheory]]
- [[MathematicalFoundationsOfMachineLearning]]

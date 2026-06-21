---
summary: Adam, AdamW, GaLore, and L-BFGS; Lagrange multipliers and KKT conditions
  — engines of ML training and memory-efficient LLM pre-training on consumer hardware.
title: Optimization Algorithms
tags:
- optimization
- machine-learning
- gradient-descent
- adam
- jax
- pytorch
- scipy
cluster: mathematics
type: article
date: 2026-05-03T00:00:00Z
status: active
canonical_id: 01KQQFWARYXY3MG4R8VWRM3CGE
---

# Optimization Algorithms: Finding the Global Minimum

Optimization is the science of finding the input $x$ that minimizes a function $f(x)$, typically representing a "loss" or "cost." In modern engineering, it is the engine that allows a neural network to "learn." In 2024, the field has shifted from mere gradient descent to **Full-Parameter Efficiency**, allowing massive models to be trained on consumer hardware.

---

## 1. The Landscape of Optimization: Gradient vs. Curvature

To find the minimum of a function, an algorithm must decide: **Which direction to go?** and **How far to step?**

### 1.1 First-Order Methods (The "Foggy Mountain" View)
Gradient Descent uses the first derivative (the gradient, $\nabla f$) to find the direction of steepest descent.
*   **Geometric Intuition:** Imagine standing on a foggy mountain at night. You can only see the ground immediately beneath your feet. To descend, you feel for the steepest downward slope and take a small step.
*   **Limitation:** It is "blind" to the curvature. If you take a step that is too large, you might overshoot the valley.

### 1.2 Second-Order Methods (The "Flashlight" View)
Newton's Method uses the gradient and the second derivative (the Hessian matrix, $\mathbf{H}$), which represents curvature.
*   **Geometric Intuition:** You use a flashlight to see the shape of the bowl around you. At every step, the algorithm fits a **paraboloid** (a quadratic bowl) to the local surface and jumps directly to its bottom.
*   **The Advantage:** It adjusts its step size automatically. On flat plateaus, it takes massive leaps; in tight corners, it takes precise, tiny steps.

---

## 2. Unconstrained Optimization: The Engines of AI

### 2.1 Adam and AdamW (The Memory-Heavy Standard)
Adam (Adaptive Moment Estimation) tracks both the average gradient (momentum) and the average squared gradient (uncentered variance).
*   **The 2024 Memory Crisis:** For every model parameter, AdamW stores two 32-bit states. For a 7B parameter model, the **optimizer states alone require 56GB of VRAM**, far exceeding consumer hardware limits.
*   **The 2024 Solution (8-bit Optimizers):** Quantizing these states to 8-bit reduces memory by **75%** with negligible accuracy loss.

### 2.2 2024 Breakthroughs: GaLore and DoRA
*   **GaLore (Gradient Low-Rank Projection):** Instead of full-rank updates, GaLore projects gradients into a low-rank subspace. It reduces optimizer memory by **65.5%**, allowing pre-training of a 7B model from scratch on a single 24GB GPU.
*   **DoRA (Weight-Decomposed LoRA):** Decomposes weights into **magnitude** and **direction**. It outperforms traditional LoRA by allowing the model to learn directional updates more effectively, bridging the gap to full-parameter tuning.

---

## 3. Constrained Optimization: The Geometry of Boundaries

Many problems have rules: "total weight must equal 1" ($g(x) = 0$) or "budget cannot be negative."

### 3.1 Lagrange Multipliers: The Tangency Condition
*   **Geometric Intuition:** Imagine your objective's contour lines (topographic map). Your constraint is a path. You reach the optimum at the exact moment your path **kisses** (is tangent to) a contour line.
*   **The Equation:** At this point, the gradients must be parallel: $\nabla f = \lambda \nabla g$.

### 3.2 KKT Conditions: The Balance of Forces
The Karush-Kuhn-Tucker (KKT) conditions generalize Lagrange for inequality constraints ($h(x) \le 0$).
*   **Geometric Intuition:** Think of a ball rolling into a corner where two walls meet. The ball stops because the "force" of gravity ($-\nabla f$) is perfectly balanced by the "push" of the walls ($\lambda \nabla h$).
*   **Complementary Slackness:** If the ball isn't touching a wall (inactive constraint), that wall's "pushing force" ($\lambda$) must be zero.

---

## 4. Convexity and Jensen's Inequality

A function is **convex** if it is "bowl-shaped" everywhere. Convex problems are the "holy grail" because any local minimum is guaranteed to be the global minimum.

### 4.1 Visualizing Jensen's Inequality
Jensen's Inequality states: $E[f(X)] \ge f(E[X])$.
*   **Visual Intuition:** Draw a secant line between any two points on a convex curve. The line always stays **above** the curve. 
    *   $E[f(X)]$ is a point on the line (the average of the outputs).
    *   $f(E[X])$ is a point on the curve (the output of the average).
    *   Geometrically, the "Average of the Function" is always greater than the "Function of the Average."

---

## 5. Quantitative Foundation: Comparative Performance

| Optimizer | Order | Memory (7B Model) | Conv. Speed | Best Use Case |
| :--- | :--- | :--- | :--- | :--- |
| **SGD + Momentum**| 1st | ~28 GB | Linear | Vision / Simple Models |
| **AdamW (32-bit)** | 1st (Adap) | **56 GB** | Fast | General NLP / LLMs |
| **8-bit AdamW** | 1st (Adap) | **14 GB** | Fast | Consumer Fine-tuning |
| **GaLore (2024)** | 1st (Adap) | **~19 GB** | Fast | **7B Pre-training** |
| **L-BFGS** | 2nd (Quasi)| Very High | **Quadratic** | Scientific Computing |

### 5.1 Learning Rate Schedulers
Finding the minimum is not just about the algorithm, but the "cooling" schedule.
*   **Cosine Annealing:** Gradually reduces the step size following a cosine curve, allowing the model to settle into the deepest, narrowest valleys of the loss landscape at the end of training.

## See Also
- [AppliedMathSurvey](AppliedMathSurvey) — The map of mathematical tools.
- [CalculusRefreshForCS](CalculusRefreshForCS) — Gradients, Hessians, and Jacobians.
- [InformationTheory](InformationTheory) — Understanding the loss function (Cross-Entropy).
- [MathematicsHub](MathematicsHub) — Central index for math topics.

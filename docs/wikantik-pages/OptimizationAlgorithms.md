---
canonical_id: 01KQQFWARYXY3MG4R8VWRM3CGE
date: 2026-05-03T00:00:00Z
cluster: mathematics
tags:
- optimization
- machine-learning
- gradient-descent
- adam
- jax
- pytorch
- scipy
title: Optimization Algorithms
summary: A deep dive into the optimization algorithms that drive machine learning
  and operations research. Covers Gradient Descent, Momentum, RMSProp, Adam, and Second-Order
  methods like L-BFGS, with integrations to JAX and PyTorch.
status: active
---

# Optimization Algorithms: Finding the Global Minimum

Optimization is the process of finding the input $x$ that minimizes (or maximizes) a function $f(x)$, often subject to constraints. In Machine Learning, $f$ is the "Loss Function," and $x$ represents the "Model Parameters."

## 1. First-Order Methods (Gradient-Based)

These methods use the first derivative (the gradient) to find the minimum.

### Stochastic Gradient Descent (SGD)
The workhorse of ML. Instead of calculating the gradient for the entire dataset, it uses a single random example (or a "mini-batch") per step.
- **Formula**: $w_{t+1} = w_t - \eta \nabla L(w_t)$
- **Pros**: Fast, can escape local minima.
- **Cons**: High variance in updates, requires careful learning rate tuning.

### Momentum and Nesterov Accelerated Gradient
Adds a "velocity" term to dampen oscillations and accelerate convergence in steep valleys.
- **Intuition**: Think of a heavy ball rolling down a hill; it gains momentum and is less likely to get stuck in small bumps.

### Adaptive Methods: RMSProp and Adam
These algorithms maintain per-parameter learning rates.
- **Adam (Adaptive Moment Estimation)**: Combines the ideas of Momentum and RMSProp. It keeps track of both the first moment (the mean) and the second moment (the uncentered variance) of the gradients. It is the **default choice** for most deep learning tasks.

## 2. Second-Order Methods

These methods use the second derivative (the Hessian) to account for the curvature of the function.

- **Newton's Method**: Uses the inverse Hessian to jump directly toward the minimum of a quadratic approximation.
- **L-BFGS**: A limited-memory version of the BFGS algorithm. It approximates the Hessian without storing it explicitly. Highly effective for small-to-medium datasets where high precision is required.

## 3. Constrained Optimization

When the solution must satisfy certain rules (e.g., "weights must sum to 1").
- **Lagrange Multipliers**: A strategy for finding the local maxima and minima of a function subject to equality constraints.
- **KKT Conditions**: Generalizes Lagrange multipliers for inequality constraints.

## 4. Open Source Implementation

### JAX: Autodiff and XLA Optimization
**JAX** is a high-performance library that can automatically differentiate Python and NumPy functions.

```python
import jax
import jax.numpy as jnp

def loss_fn(w, x, y):
    return jnp.mean((jnp.dot(x, w) - y)**2)

# Get the gradient function automatically
grad_fn = jax.grad(loss_fn)

# Use it in an optimizer (e.g., optax)
```

### PyTorch: The `torch.optim` ecosystem
PyTorch provides a suite of ready-to-use optimizers.

```python
import torch.optim as optim

optimizer = optim.Adam(model.parameters(), lr=1e-3)
# In the training loop:
optimizer.zero_grad()
loss.backward()
optimizer.step()
```

## See Also
- [[CalculusRefreshForCS]] — The math behind the gradients.
- [[MathematicalFoundationsOfMachineLearning]] — How optimization fits into the ML lifecycle.
- [[LinearProgrammingFoundations]] — For linear constraints.

---
canonical_id: 01KQ12YDV1FC565JEW2X6HJA23
title: Gradient Descent and Optimizers
type: article
cluster: machine-learning
status: active
date: '2026-05-15'
tags:
- gradient-descent
- adam
- sgd
- optimizers
- deep-learning
summary: Technical analysis of optimization algorithms (SGD, AdamW, Lion) and concrete implementations of learning rate schedules in PyTorch.
related:
- DeepLearningFundamentals
- LinearAlgebra
- NeuralNetworkArchitectures
- LLMFineTuning
hubs:
- MachineLearningHub
auto-generated: false
---
# Gradient Descent and Optimizers

Optimizing a neural network involves navigating a high-dimensional loss landscape to find a global minimum. The choice of optimizer and learning rate (LR) schedule determines convergence speed and final model generalization.

## 1. Stochastic Gradient Descent (SGD) with Momentum
SGD updates weights using a subset (mini-batch) of data. **Momentum** adds a fraction of the previous update to the current one, helping the optimizer navigate out of local minima and dampening oscillations in high-curvature regions.

Update Rule:
$$
v_{t} = \gamma v_{t-1} + \eta \nabla L(\theta)
$$
$$
\theta = \theta - v_{t}
$$

Where$\gamma \approx 0.9$and$\eta$is the learning rate.
## 2. Adaptive Optimizers: Adam and AdamW
**Adam** (Adaptive Moment Estimation) computes individual learning rates for each parameter by tracking the first moment (mean) and second moment (uncentered variance) of the gradients.

**AdamW** is the modern standard, which decouples **Weight Decay** from the gradient update. In vanilla Adam, L2 regularization is added to the loss, which interacts poorly with adaptive learning rates. AdamW applies weight decay directly to the weights.

## 3. Lion (EvoLved Sign Momentum)
Lion is a memory-efficient optimizer that uses the **sign** of the gradient update rather than the magnitude. It requires only the first moment, saving 50% of the optimizer state memory compared to AdamW.

## 4. Learning Rate Schedules
A static learning rate rarely converges to the optimal minimum. Schedules adjust$\eta$over time.

### Concrete Example: Cosine Annealing with Warmup in PyTorch
Warmup prevents large gradients from destabilizing the model early in training. Cosine annealing then smoothly decays the LR to a minimum value.

```python
import torch
import torch.optim as optim
from torch.optim.lr_scheduler import LambdaLR
import math

# Hyperparameters
max_lr = 3e-4
warmup_steps = 1000
total_steps = 10000

# Model and Optimizer
model = torch.nn.Linear(10, 1)
optimizer = optim.AdamW(model.parameters(), lr=max_lr, weight_decay=0.01)

def lr_lambda(current_step):
    # 1. Linear Warmup
    if current_step < warmup_steps:
        return float(current_step) / float(max(1, warmup_steps))
    
    # 2. Cosine Annealing
    progress = float(current_step - warmup_steps) / float(max(1, total_steps - warmup_steps))
    return 0.5 * (1.0 + math.cos(math.pi * progress))

scheduler = LambdaLR(optimizer, lr_lambda)

# Training loop
for step in range(total_steps):
    optimizer.step()
    scheduler.step()
```

## 5. Gradient Clipping
To prevent "exploding gradients" in deep networks (especially Transformers), gradients are clipped by norm. If the global norm$||g||$ exceeds a threshold (typically 1.0), the gradient is rescaled.
```python
torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
```

## Summary of Technical implementation added
- Detailed the math behind **SGD with Momentum**.
- Explained the difference between **Adam and AdamW** regarding weight decay.
- Introduced **Lion** as a memory-efficient alternative.
- Provided a complete **PyTorch implementation of Cosine Annealing with Warmup**.
- Included a concrete example of **Gradient Clipping**.

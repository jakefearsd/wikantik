---
canonical_id: 01KQ0P44VCJ2Q4A951GEM64S3A
title: Reinforcement Learning Fundamentals
type: article
cluster: machine-learning
status: active
date: '2026-04-26'
summary: The foundations of reinforcement learning — agents, environments, rewards,
  value functions, policy methods — and the practical challenges that distinguish
  RL from supervised learning.
tags:
- reinforcement-learning
- machine-learning
- rl
- agents
related:
- TreeBasedModels
- TransformerArchitecture
hubs:
- MLHub
---
# Reinforcement Learning Fundamentals

Reinforcement learning (RL) trains agents to take actions in environments to maximize cumulative reward. Unlike supervised learning, there are no labels — only feedback in the form of rewards.

RL has produced spectacular successes (game playing, robotics) and quiet successes (recommendation systems, ad bidding) but is harder to apply than supervised learning.

## The setup

Components:
- **Agent**: makes decisions
- **Environment**: state of the world
- **Action**: what the agent does
- **Reward**: feedback signal
- **Policy**: agent's decision rule
- **Value function**: expected future reward

Loop:
1. Observe state
2. Choose action (according to policy)
3. Environment transitions to new state, returns reward
4. Update policy/value function
5. Repeat

## Markov Decision Processes (MDPs)

The mathematical framework:
- State space S
- Action space A
- Transition function P(s'|s,a)
- Reward function R(s,a,s')
- Discount factor γ ∈ [0, 1)

Markov property: future depends only on current state, not history.

Many real problems aren't truly Markov but are approximated as such.

## The objective

Maximize expected discounted return:
G = R₁ + γR₂ + γ²R₃ + ...

Discount factor γ trades immediate vs future reward. γ near 0: myopic. γ near 1: far-sighted.

## Value functions

### State value V(s)

Expected return from state s, following policy π.

### Action value Q(s, a)

Expected return from state s, taking action a, then following π.

The Bellman equation relates values across states:
V(s) = E[R + γV(s')]

This recursive structure underlies most RL algorithms.

## Exploration vs exploitation

The fundamental tension:
- Exploit: take known-good actions
- Explore: try new actions to learn

Pure exploitation gets stuck in local optima. Pure exploration never benefits from learning.

Strategies:
- **ε-greedy**: random action with probability ε, else best
- **UCB**: balance estimated value with uncertainty
- **Thompson sampling**: sample from posterior
- **Entropy bonuses**: reward exploration directly

## Algorithm families

### Value-based

Learn Q(s, a); act greedily.

- **Q-learning**: classic; off-policy
- **DQN**: Q-learning with neural networks; Atari breakthrough
- **Rainbow**: DQN with many improvements

### Policy-based

Learn policy directly.

- **REINFORCE**: vanilla policy gradient
- **PPO**: proximal policy optimization; standard workhorse
- **TRPO**: trust region; theoretical predecessor

### Actor-critic

Combine policy (actor) and value (critic).

- **A3C / A2C**: parallel actor-critic
- **PPO with value function**: PPO is technically actor-critic
- **SAC**: soft actor-critic; strong for continuous control

### Model-based

Learn a model of the environment; plan with it.

- **MuZero**: learns model and plans (AlphaZero descendant)
- **Dreamer**: world models for high-dim observations

Sample efficiency advantage; hard to do well.

## Modern practice

For most applied RL:
- **PPO**: discrete or continuous, easy to tune, robust
- **SAC**: continuous control, sample efficient
- **DQN variants**: Atari-style discrete

Off-the-shelf libraries: Stable-Baselines3, RLlib, CleanRL.

## Sample efficiency

RL needs many environment interactions. Often millions to billions.

For simulation: tractable.
For real world: prohibitive.

Approaches:
- Sim-to-real transfer (train in simulation, deploy in real)
- Offline RL (learn from logged data)
- Meta-learning (transfer across tasks)

## Reward design

Probably the hardest part of RL.

### Sparse rewards

Reward only at goal. Agent rarely succeeds; learning is slow.

### Reward shaping

Intermediate rewards guide learning. Risky: can be exploited.

### Reward hacking

Agents find unintended ways to get reward. Common and creative.

Examples: agent that pauses game forever; cleaning robot that breaks plates to "clean" more.

### Reward from human feedback (RLHF)

Humans rate outputs; train reward model; train policy. Powers ChatGPT and similar.

## Stability

RL is notoriously unstable:
- Hyperparameter-sensitive
- Variance across random seeds
- Hard to debug

Best practices:
- Multiple seeds
- Compare to baselines
- Track many metrics
- Fix everything before tuning hyperparameters

## Distributional / safe RL

For safety-critical applications:
- Constrained RL (constraints on actions/states)
- Distributional RL (model return distribution, not just mean)
- Risk-sensitive policies

Active research area.

## Major RL successes

### Game playing

- Atari (DQN, 2013)
- Go (AlphaGo, 2016)
- StarCraft, Dota (2018-2019)
- Diplomacy (Cicero, 2022)

### Robotics

- Manipulation (Boston Dynamics, OpenAI's hand)
- Locomotion (quadrupeds, humanoids)
- Sim-to-real successes

### Industrial

- Data center cooling (Google)
- Chip design (Google)
- Recommender systems (many companies)

### LLM training

- RLHF for alignment (ChatGPT, Claude)
- Constitutional AI (Anthropic)

## When NOT to use RL

If supervised learning would work:
- You have labeled data
- Action consequences are immediate
- One-shot decisions, not sequential

Don't reach for RL out of fashion.

## Common failure patterns

### Reward hacking

Rewards optimized in unintended ways.

### Distribution shift

Policy trained in one distribution fails in another.

### Catastrophic forgetting

Continuing to train can degrade earlier behavior.

### Bad reward function

If you can't write a good reward, RL won't save you.

### Insufficient exploration

Stuck in local optima.

### Overfitting to simulation

Sim-to-real gap. Domain randomization helps.

## Practical advice

1. Start with the simplest possible setup
2. Get a baseline working before optimizing
3. Check for bugs before tuning hyperparameters
4. Multiple random seeds; report variance
5. If RL isn't working, ask whether the problem is RL-shaped at all

## Further Reading

- [TreeBasedModels](TreeBasedModels) — Different ML paradigm
- [TransformerArchitecture](TransformerArchitecture) — Often the policy network
- [ML Hub](MLHub) — Cluster index

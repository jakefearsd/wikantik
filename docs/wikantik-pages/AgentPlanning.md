---
title: Agent Planning
type: article
tags:
- agent
- plan
- pattern
summary: Agent Planning If you are reading this, you are likely past the stage of
  merely chaining API calls together.
auto-generated: true
---
# Agent Planning

If you are reading this, you are likely past the stage of merely chaining API calls together. You understand that the modern AI agent is not a glorified wrapper around an LLM endpoint; it is a complex, stateful, reasoning system. The transition from simple prompt-response interaction to reliable, multi-step task completion requires a rigorous understanding of *architectural* patterns, not just prompt engineering tricks.

This tutorial serves as a comprehensive technical deep dive into the established and emerging patterns governing how sophisticated agents plan, execute, recover from failure, and coordinate across multiple specialized entities. We are moving beyond the "magic" of the prompt and into the verifiable, engineering discipline of [agentic workflow design](AgenticWorkflowDesign).

---

## 1. Introduction

The fundamental challenge in building complex AI agents is the inherent gap between the LLM's ability to *simulate* reasoning and the requirement for *guaranteed*, verifiable execution. A single, massive prompt asking an LLM to "Plan and execute X" often results in a hallucinated, linear narrative that fails spectacularly when faced with real-world constraints, external API failures, or ambiguous state transitions.

The solution is to externalize the control flow. We must treat the agent not as a single monolithic function call, but as a **State Machine** governed by explicit, modular patterns.

### 1.1 Defining the Core Components

Before diving into patterns, we must establish the vocabulary:

*   **Agent:** The computational entity responsible for decision-making, reasoning, and action selection.
*   **Task:** The high-level, abstract goal provided by the user (e.g., "Analyze Q3 sales data and draft a competitive positioning memo").
*   **Plan:** The structured, ordered sequence of necessary sub-tasks, tool calls, and decision points required to achieve the Task. This is the *roadmap*.
*   **Execution:** The iterative process of executing the steps defined in the Plan, managing state changes, and handling the outputs of external tools or subsequent reasoning steps.
*   **Orchestrator:** The meta-controller responsible for managing the Plan, invoking the appropriate Agents, monitoring the State, and deciding the next macro-step (e.g., "If Step 3 fails, revert to Step 1 and try Tool B").

### 1.2 The Limitations of Naive Planning

The most common pitfall—and the one we must explicitly avoid—is relying solely on the LLM's internal reasoning to generate the entire plan in one go.

**The Flaw:** LLMs are excellent at *plausibility* but poor at *guaranteed correctness* over long horizons. They suffer from context window decay, internal contradiction, and an inability to perform true symbolic search (like A* or Dijkstra's) without explicit scaffolding.

**The Pattern Shift:** We must enforce a separation of concerns: **Planning $\rightarrow$ Validation $\rightarrow$ Execution $\rightarrow$ Reflection.**

---

## 2. Core Planning Patterns

Planning is not a single action; it is a multi-stage process of decomposition, refinement, and validation. The goal is to transform a vague, high-level objective into a Directed Acyclic Graph (DAG) of actionable steps.

### 2.1 Hierarchical Task Network (HTN) Planning

HTN planning is arguably the most robust, theoretically grounded approach for complex, structured tasks. It mirrors how human experts break down problems: by decomposing a high-level goal into smaller, manageable sub-goals, which are themselves decomposed until they reach primitive, executable actions.

**Mechanism:**
1.  **Goal Definition:** Define the top-level goal ($G_{top}$).
2.  **Method Library:** Maintain a library of *methods*. Each method specifies how a complex task ($T$) can be decomposed into a sequence of sub-tasks ($\{t_1, t_2, ..., t_n\}$) and preconditions ($\text{Pre}(T)$).
3.  **Decomposition:** The planner recursively selects a method applicable to the current sub-goal until all resulting tasks are primitive (i.e., they map directly to a tool call or a single LLM prompt).

**Expert Insight:** HTN excels because it constrains the search space using domain knowledge (the methods). You are not asking the LLM to *invent* a plan; you are asking it to *select* the correct sequence from a pre-vetted set of known, successful decomposition strategies.

**Pseudo-Structure:**

```pseudocode
FUNCTION Plan_HTN(Goal, Current_State, Method_Library):
    IF Goal is Primitive:
        RETURN [Action(Goal)]
    
    Best_Plan = NULL
    FOR Method IN Method_Library WHERE Method.AppliesTo(Goal) AND Method.Preconditions(Current_State):
        Sub_Goals = Method.Decompose(Goal)
        
        // Recursive Call
        Sub_Plan = Plan_HTN(Sub_Goals, Current_State, Method_Library)
        
        IF Sub_Plan is valid:
            IF Best_Plan is NULL OR Cost(Sub_Plan) < Cost(Best_Plan):
                Best_Plan = Sub_Plan
    
    RETURN Best_Plan
```

### 2.2 Tree-of-Thought (ToT) and Graph-of-Thought (GoT) Planning

While HTN is deterministic (guided by methods), ToT and GoT address the *search* aspect of planning, allowing the agent to explore multiple plausible paths simultaneously, which is crucial when the optimal path is non-obvious.

**Tree-of-Thought (ToT):**
This pattern treats the planning process as a search tree. At each node (a decision point), the agent generates $K$ potential next steps (hypotheses). It then evaluates these $K$ paths using a specialized *evaluator* (often a separate, highly constrained LLM call or a heuristic function) to score their potential success. The process repeats: expand the highest-scoring branches until a leaf node (a complete plan) is reached.

**Graph-of-Thought (GoT):**
GoT is an evolution of ToT. It acknowledges that plans are not strictly linear. A GoT allows the agent to revisit nodes, merge paths, or use one successful sub-plan to inform the *re-planning* of a previously completed, but now suboptimal, segment. This is vital for complex, iterative research tasks.

**Edge Case Handling (The "Dead End"):**
In both ToT and GoT, the primary failure mode is **Search Space Explosion**. If the branching factor ($K$) is too high, the computational cost becomes intractable. Experts must implement pruning heuristics:
1.  **Depth Limiting:** Stop searching after $D$ levels unless a high-confidence path is found.
2.  **Beam Search:** Instead of keeping *all* $K$ paths, only keep the top $B$ (Beam Width) most promising paths at each level. This trades optimality for tractability.

### 2.3 State-Space Search Planning (The Formal Approach)

For maximum rigor, the planning process should ideally map to classical AI planning problems, such as those solved by PDDL (Planning Domain Definition Language). While LLMs are not PDDL solvers, we can force them into this paradigm.

**The Pattern:** The agent must maintain a formal, explicit representation of the world state ($\mathcal{S}$). Every action ($A$) must be defined by:
1.  **Preconditions ($\text{Pre}(A)$):** The set of facts that *must* be true in $\mathcal{S}$ for $A$ to be valid.
2.  **Effects ($\text{Eff}(A)$):** The set of facts that *become* true or false in $\mathcal{S}$ after $A$ executes.

The planning loop then becomes a search for a sequence of actions $\langle A_1, A_2, ..., A_n \rangle$ such that:
$$\text{Pre}(A_1) \subseteq \mathcal{S}_{initial}$$
$$\text{Pre}(A_{i+1}) \subseteq \mathcal{S}_{i}$$
$$\mathcal{S}_{final} = \text{Apply}(\text{Eff}(A_n), \dots, \text{Apply}(\text{Eff}(A_1), \mathcal{S}_{initial}))$$

**Practical Implementation Note:** This requires the LLM to act as a sophisticated *parser* and *validator* against a rigid schema, rather than a free-form generator. The prompt must include the full schema definition for $\mathcal{S}$, $\text{Pre}$, and $\text{Eff}$ for every available tool.

---

## 3. Execution Management Patterns

A plan is merely a hypothesis. Execution is the empirical test. This section details how to manage the execution lifecycle, which is where most naive agents collapse.

### 3.1 The Iterative Loop Pattern (The Core Engine)

All robust agents operate on a continuous loop, not a linear script. This loop must manage state, execute, and then re-evaluate.

**The Cycle:**
1.  **Check Goal:** Is the overall task complete based on the current state? If yes, terminate.
2.  **Determine Next Step:** Based on the current state ($\mathcal{S}_t$) and the remaining plan, what is the single most logical next action ($A_{t+1}$)?
3.  **Execute Action:** Call the tool/API/LLM function associated with $A_{t+1}$.
4.  **Observe State Change:** Receive the output ($\text{Output}_t$) and update the state ($\mathcal{S}_{t+1} = \text{Update}(\mathcal{S}_t, \text{Output}_t)$).
5.  **Reflect/Re-Plan:** Analyze $\mathcal{S}_{t+1}$. Does this state invalidate assumptions made earlier? If so, trigger a localized re-planning cycle.

### 3.2 State Persistence and Context Management

The single greatest technical hurdle is state management. If the agent loses track of what it knows, it fails.

**Pattern:** **Externalized, Structured State Store.**
The agent's working memory *must* be externalized into a structured database or key-value store, not held solely within the context window.

*   **State Schema:** The state must be atomic and queryable (e.g., `{"user_profile": {...}, "data_retrieved": [{"source": "API_X", "data": [...]}, ...], "intermediate_findings": [...]}`).
*   **State Update Mechanism:** Every tool call must return not just its result, but also a structured JSON object detailing *how* the state should be updated.

**Example of State Update Failure:**
*   *Bad:* Agent reads a document and summarizes it. The summary is lost if the next step requires the raw data.
*   *Good:* Agent reads document $\rightarrow$ Tool returns `{"status": "success", "state_update": {"documents_processed": 1, "raw_data_ref": "doc_id_123"}}`. The orchestrator logs this reference, ensuring the data is available for later steps, even if the LLM forgets the summary.

### 3.3 Error Handling and Recovery Patterns

This is where most "expert" systems fail in practice. Real-world execution is messy.

#### A. Retry Mechanisms (Transient Errors)
For API calls that fail due to rate limiting, network hiccups, or temporary service unavailability (HTTP 429, 503), the pattern is simple: **Exponential Backoff Retry.**
*   Attempt 1: Wait 1s, Retry.
*   Attempt 2: Wait 2s, Retry.
*   Attempt 3: Wait 4s, Retry.
*   If failure persists after $N$ attempts, escalate to a structural failure (see below).

#### B. Exception Handling (Expected Failures)
When a tool fails because the *input* was wrong (e.g., trying to parse text as an integer), the agent must not crash.
*   **Pattern:** **Input Validation & Error Classification.** The agent must classify the error:
    1.  **Schema Error:** The tool was called correctly, but the *data* violates the tool's expected schema (e.g., missing required field). *Action: Re-plan the preceding step to gather the missing data.*
    2.  **Logic Error:** The tool executed, but the result is nonsensical or contradictory (e.g., a financial API returns negative revenue for a profitable quarter). *Action: Trigger Reflection.*

#### C. The Reflection/Self-Correction Loop (The Gold Standard)
This is the most advanced pattern. When the agent encounters an unrecoverable error or a significant deviation from the expected path, it must pause execution and enter a meta-reasoning phase.

**Mechanism:** The Orchestrator feeds the entire execution history ($\mathcal{H}$), the failed step ($\text{Step}_i$), the error message ($\text{Error}_i$), and the original Goal ($G_{top}$) back into the LLM with a specific prompt: *"Analyze the history $\mathcal{H}$ and the failure $\text{Error}_i$. Propose a revised, corrected plan segment $\langle A'_{i}, ..., A'_{n} \rangle$ that addresses the root cause."*

This forces the LLM to act as a debugger, not just a continuation engine.

---

## 4. Multi-Agent Coordination Patterns

When a single agent cannot encapsulate the necessary expertise (e.g., one agent for data retrieval, one for statistical modeling, and one for natural language drafting), we must employ Multi-Agent Systems (MAS). The challenge shifts from *planning* to *coordination*.

### 4.1 Linear Orchestration (The Assembly Line)

This is the simplest MAS pattern, analogous to a sequential pipeline. Agent A passes its output to Agent B, which passes its output to Agent C, and so on.

**Use Case:** Highly predictable workflows where the output of one stage is the *sole* required input for the next (e.g., Data Ingestion $\rightarrow$ Cleaning $\rightarrow$ Visualization).

**Limitation:** It is brittle. If Agent B fails, the entire pipeline halts, and there is no inherent mechanism for Agent A to correct its initial output.

### 4.2 Adaptive Orchestration (The Switchboard)

This pattern introduces conditional routing based on the output of an initial assessment agent (the "Triage Agent").

**Mechanism:**
1.  **Triage Agent:** Receives the initial Task. Its sole job is to classify the task and determine the optimal *sub-workflow* or *specialist agent* required.
2.  **Router/Orchestrator:** Reads the Triage Agent's output (e.g., `{"classification": "Financial Analysis", "required_agent": "QuantAgent"}`).
3.  **Delegation:** The Orchestrator invokes the specialized agent, passing only the necessary context.

**Expert Enhancement (The Fallback Graph):** A truly robust adaptive system maps out a **Fallback Graph**. If the Triage Agent classifies the task as "Unknown," it doesn't fail; it routes the task to a "Generalist Agent" which, in turn, triggers a human-in-the-loop review or attempts a sequence of fallback agents (e.g., Triage $\rightarrow$ Generalist $\rightarrow$ Human Review).

### 4.3 Hierarchical Orchestration (The Management Structure)

This is the most complex and powerful model, mimicking organizational structure. It involves multiple layers of control.

**Structure:**
*   **Level 1: The Manager Agent (The Planner):** Receives the Goal. It breaks the Goal into 3-5 major Milestones ($M_1, M_2, M_3$). It does *not* execute the steps; it delegates.
*   **Level 2: Specialist Agents (The Teams):** Each agent is responsible for a major domain (e.g., `Data_Retrieval_Team`, `Modeling_Team`, `Reporting_Team`). They receive a Milestone ($M_i$) and are responsible for generating a detailed sub-plan for that milestone.
*   **Level 3: Worker Agents (The Hands):** These are the low-level executors that interact with tools and APIs, executing the primitive steps defined by the Specialist Agent.

**Workflow Flow:**
1.  Manager defines $M_1$.
2.  Manager delegates $M_1$ to `Data_Retrieval_Team`.
3.  `Data_Retrieval_Team` generates a sub-plan and executes it via Worker Agents.
4.  `Data_Retrieval_Team` passes the *validated, structured results* back to the Manager.
5.  Manager reviews results and delegates $M_2$ to `Modeling_Team`.

**Advantage:** Fault isolation. If the `Modeling_Team` fails, the `Data_Retrieval_Team`'s work remains safely stored and validated, allowing the Manager to retry only the modeling phase without re-running data collection.

---

## 5. Meta-Patterns for Robustness and Optimization

The patterns above define *what* the agent does (plan, execute, coordinate). These meta-patterns define *how well* it does it—how it learns, adapts, and optimizes its own process.

### 5.1 Reflection and Self-Critique (The Inner Critic)

Reflection is the mechanism by which the agent evaluates its own output *before* presenting it to the user or passing it to the next agent. It is the implementation of the "Inner Critic."

**Process:**
1.  **Generate Output:** Agent $A$ produces $\text{Output}_A$.
2.  **Critique Prompt:** The Orchestrator invokes a dedicated `CriticAgent` with the prompt: *"Review $\text{Output}_A$ against the original Goal $G_{top}$ and the constraints $C$. Identify any logical gaps, factual inconsistencies, or areas where the output is insufficiently detailed. Return a JSON object detailing the required revisions."*
3.  **Revision:** If the critique is substantial, the Orchestrator forces Agent $A$ to re-generate $\text{Output}'_A$ based on the critique.

**Advanced Critique Vectors:**
*   **Completeness Critique:** Did it address every constraint listed in $G_{top}$?
*   **Consistency Critique:** Does the conclusion contradict any premise stated in the input data?
*   **Plausibility Critique:** Is the reasoning path sound, even if the data is correct? (This tests for logical leaps).

### 5.2 Tool Selection and Tool Use Strategy

Treating tools as mere functions is insufficient. The agent must possess a meta-skill: **Tool Selection Strategy**.

**The Problem:** An agent might have access to `Search_Web()`, `Query_Database()`, and `Calculate_Statistics()`. If the task is "What was the market reaction to the Q3 earnings report?", a naive agent might call all three sequentially.

**The Pattern:** The agent must reason about the *information gap* and select the *minimal necessary set* of tools.

1.  **Hypothesize Information Needs:** Based on $G_{top}$, list required data types (e.g., "Need financial metrics," "Need public sentiment").
2.  **Map Needs to Tools:** Map data types to available tools.
3.  **Prioritize:** Determine the optimal call order to minimize redundant calls and maximize information gain per call.

**Pseudo-Logic for Tool Selection:**

```pseudocode
FUNCTION Select_Tools(Goal, Available_Tools):
    Required_Concepts = Extract_Concepts(Goal)
    Tool_Candidates = []
    
    FOR Concept IN Required_Concepts:
        Best_Tool = Find_Tool_By_Semantic_Match(Concept, Available_Tools)
        IF Best_Tool IS NOT NULL AND Best_Tool NOT IN Tool_Candidates:
            Tool_Candidates.append(Best_Tool)
            
    // Apply redundancy check (e.g., if Tool A and Tool B both cover 'Sentiment', pick the one with better reliability score)
    Final_Tool_Set = Prune_Redundant_Tools(Tool_Candidates)
    RETURN Final_Tool_Set
```

### 5.3 Memory Management Patterns (Long-Term Context)

For agents operating over days or weeks (e.g., research assistants), the context window is a non-renewable resource.

**Pattern: Vector Store Retrieval Augmented Generation (RAG) for Memory.**
All significant interactions, retrieved documents, and finalized sub-plans must be chunked, embedded (using a high-dimensional embedding model), and stored in a Vector Database.

When the agent needs context, it does not rely on the prompt history; it executes a **Semantic Search Query** against its own memory store. This allows the agent to recall facts from months ago with the same fidelity as if they were mentioned in the last three turns.

**The Metadata Layer:** Crucially, the vector store must be augmented with metadata: `{"source_type": "API_CALL", "confidence_score": 0.92, "timestamp": "..."}`. This allows the agent to filter memory retrieval, ignoring low-confidence or outdated information when making a decision.

---

## 6. Edge Case Analysis

To truly master this domain, one must understand the failure modes at the intersection of these patterns.

### 6.1 The Conflict Resolution Pattern (The Arbitration Layer)

What happens when two specialized agents, operating under different assumptions, provide contradictory results?

**Scenario:**
*   **Agent A (Financial):** Reports revenue growth of 15% based on Q3 filings.
*   **Agent B (Marketing):** Reports a 40% increase in web traffic, suggesting massive, uncaptured growth.
*   **Goal:** Draft a comprehensive growth narrative.

**The Solution:** The Orchestrator must invoke an **Arbitration Agent**. This agent is not a generalist; it is specifically prompted to act as a *Devil's Advocate* or a *Scientific Reviewer*.

**Arbitration Prompt Directives:**
1.  Identify the core conflict: (Revenue vs. Traffic).
2.  Determine the necessary missing link: (Conversion Rate, Cost Per Acquisition, etc.).
3.  Formulate a *new, specific query* that, if answered, resolves the conflict (e.g., "Query the CRM database for the conversion rate between web traffic and paying customers for Q3").

This forces the system to self-diagnose the *missing piece of information* rather than just summarizing the conflicting data points.

### 6.2 The Self-Limiting/Guardrail Pattern

This is a critical safety and reliability layer, often implemented outside the core reasoning loop. It acts as a final veto.

**Implementation:** A set of hard-coded, non-negotiable rules (guardrails) that check the *final proposed action* or *final output* against established safety, ethical, or technical boundaries.

**Examples of Guardrails:**
*   **PII Leakage Check:** Does the output contain any pattern matching SSN, credit card numbers, or internal identifiers? (If yes, redact and flag).
*   **Scope Drift Check:** Does the proposed action deviate from the initial scope defined by the user's prompt by more than $X\%$? (If yes, halt and request explicit user re-authorization).
*   **Tool Overuse Check:** Has the agent called the same high-cost/rate-limited tool more than $N$ times in the last hour? (If yes, throttle and suggest alternative data sources).

### 6.3 The Meta-Learning Pattern (Continuous Improvement)

The ultimate goal for research is not just to solve the task, but to improve the *process* of solving the task.

**Mechanism:** Every completed, successful, and critically reviewed workflow must be logged as a **Golden Example**.
1.  **Logging:** Store the entire sequence: $\langle \text{Initial State}, \text{Plan}_{successful}, \text{Execution History}, \text{Final State} \rangle$.
2.  **Pattern Extraction:** Periodically, a separate meta-agent analyzes these successful logs to identify recurring, optimal patterns.
3.  **Knowledge Base Update:** If 100 successful workflows all utilized the HTN decomposition method $M_{finance\_report}$, the system automatically updates the `Method_Library` used by the primary planner, making the pattern available for future, similar tasks without explicit retraining.

---

## Conclusion

To summarize for those who prefer bullet points: building an expert agent is not about making the LLM smarter; it is about building a **smarter scaffolding around the LLM**.

The evolution of agentic systems follows a clear progression of complexity:

1.  **Basic:** Single-shot prompting (Brittle, Context-limited).
2.  **Intermediate:** Linear [Tool Calling](ToolCalling) (Stateful, but non-recoverable).
3.  **Advanced:** HTN/ToT Planning (Structured, Search-aware).
4.  **Expert:** Hierarchical, Multi-Agent Orchestration with Reflection and Guardrails (Robust, Self-Correcting, and Scalable).

Mastering this field requires treating the LLM as a highly capable, but fallible, *reasoning module* within a much larger, rigorously engineered **Control Plane**. The true intellectual property is not the prompt, but the orchestration logic that manages the state, enforces the search boundaries, and dictates the recovery protocols when the inevitable failure occurs.

If you implement these patterns—especially the combination of **HTN Planning $\rightarrow$ External State Management $\rightarrow$ Reflection Loop $\rightarrow$ Hierarchical Orchestration**—you will have moved beyond mere prototyping and into the realm of genuinely reliable, enterprise-grade autonomous systems. Now, go build something that doesn't crash when the API returns an HTTP 400 error.

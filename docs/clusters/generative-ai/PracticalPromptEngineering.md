# Practical Prompt Engineering

Prompt engineering sounds technical. It isn't. It's the skill of communicating clearly with a system that takes you literally, has no context beyond what you provide, and produces dramatically better results when given better instructions.

You already do this with humans: you explain background, set expectations, provide examples of what you want. Prompting is the same skill, tuned for a different audience.

## The Fundamental Principle

**Specificity beats cleverness.** The difference between a mediocre prompt and a great prompt is almost never about finding the "right magic words." It's about providing more context, more constraints, and more examples.

| Prompt | Quality | Why |
|--------|---------|-----|
| "Write a blog post about productivity" | Poor | No constraints, no audience, no tone, no length |
| "Write a 600-word blog post about productivity tips for remote software engineers, in a conversational but professional tone, with 5 specific actionable tips" | Good | Constrained length, audience, tone, structure |
| Same as above + "Here's an example of a post I liked: [paste example]. Match this style." | Excellent | Concrete example anchors the style |

## The Five Pillars of Effective Prompts

### 1. Role and Context

Tell the model who it is and what situation it's in. This primes the statistical patterns toward relevant outputs.

**Weak**: "Explain DNS."
**Strong**: "You are a senior DevOps engineer mentoring a junior developer who understands HTTP but has never configured DNS. Explain DNS, focusing on the concepts they'll need for their first deployment."

The role doesn't need to be elaborate. Even a sentence of context dramatically improves relevance.

### 2. Task Specification

Be explicit about what you want. Don't make the model guess your intent.

**Weak**: "Help me with my resume."
**Strong**: "Review my resume (pasted below). Identify the 3 weakest bullet points and rewrite them using the STAR format (Situation, Task, Action, Result). Keep each bullet under 25 words."

### 3. Format and Constraints

Specify the output format. Models follow formatting instructions well.

- "Respond in bullet points"
- "Use a markdown table with columns: Feature, Pros, Cons"
- "Keep your response under 200 words"
- "Start with a one-sentence summary, then elaborate"
- "Do NOT include caveats or disclaimers"

### 4. Examples (Few-Shot Prompting)

The single most powerful prompting technique: show the model what you want by providing 1-3 examples.

```
Convert these meeting notes into action items.

Example input: "Sarah mentioned we should update the docs. John said he'd review the PR by Friday."
Example output:
- [ ] Update documentation (Sarah)
- [ ] Review PR by Friday (John)

Now convert these notes:
[paste your actual meeting notes]
```

Few-shot prompting works because the model identifies the pattern from your examples and applies it to new input. It's especially powerful for tasks where the desired output format is unusual or domain-specific.

### 5. Iteration Instructions

Tell the model how to handle uncertainty or how you want to interact.

- "If anything is unclear, ask me before proceeding"
- "Give me 3 options and explain the trade-offs"
- "Think step-by-step before giving your final answer"
- "After your response, suggest 3 ways I could improve my prompt"

## Common Prompting Patterns

### Chain of Thought

Ask the model to show its reasoning before giving an answer. This dramatically improves accuracy on complex problems.

**Without**: "Is it cheaper to rent or buy a house in Austin, Texas if I plan to stay 5 years?" → Gets a generic answer.

**With**: "Think through this step-by-step, showing your reasoning: Is it cheaper to rent or buy a house in Austin, Texas if I plan to stay 5 years? Consider current prices, mortgage rates, property taxes, maintenance, opportunity cost of the down payment, and transaction costs." → Gets a structured analysis.

### Persona Pattern

"Act as a [role] who [specific trait]." The role primes the model's knowledge; the trait shapes its communication style.

- "Act as a tax accountant who explains things simply"
- "Act as a skeptical code reviewer who focuses on edge cases"
- "Act as a writing editor who never adds words, only cuts them"

### Template Filling

Provide a template with blanks. The model fills them in, which constrains the output to exactly your format.

```
Fill in this template for each of the following 5 products:

Product: [name]
Target customer: [one sentence]
Key differentiator: [one sentence]
Biggest risk: [one sentence]
```

### Adversarial Prompting

Ask the model to argue against itself. Excellent for stress-testing ideas.

- "Here's my business plan. Now argue against it as a skeptical investor. Be ruthless."
- "I've written this code. Find every bug, edge case, and security vulnerability."
- "Here's my article. What would a hostile reader criticize?"

## The Iteration Loop

The best outputs almost never come from the first prompt. The real skill is iteration:

1. **Send initial prompt** — get a first draft
2. **Evaluate** — what's good? What's wrong? What's missing?
3. **Refine** — "That's close but too formal. Make it more conversational. Also, the second point is wrong — here's the correct information: ..."
4. **Repeat** — 2-4 iterations usually converges on something good

**Key insight**: Correcting the model is not failure. It's the workflow. A prompt that produces a perfect first response is rare and not necessary. The speed advantage of AI comes from the iteration being fast, not from the first response being perfect.

## What Running a Local Model Teaches You About Prompting

When you [run a local LLM](RunningLocalLlms), you learn things about prompting that cloud APIs obscure:

- **Temperature**: You can set how "creative" vs. "deterministic" the model is. At temperature 0, the same prompt gives the same output every time. At high temperature, outputs are more varied but less reliable. Cloud interfaces hide this setting but it's always there.
- **System prompts**: You see exactly how the system prompt (the invisible instructions that make ChatGPT "ChatGPT" rather than raw GPT) shapes behaviour. This demystifies why models have "personalities."
- **Context window**: You feel the hard limit of how much text the model can consider at once. This teaches you to front-load important information.
- **Token economics**: You see exactly how many tokens your prompt and response consume, which builds intuition for when you're being wasteful.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Asking the model to do 5 things in one prompt | Break into sequential prompts — the model focuses better on one task |
| Not providing examples | Add 1-3 examples of desired output — worth the extra 30 seconds |
| Accepting the first response | Iterate. "Make it shorter." "Be more specific." "That's wrong — here's why." |
| Using AI for factual recall | Verify any facts. Use Perplexity (with citations) or search engines for factual queries |
| Over-engineering the prompt | Start simple. Add specificity only if the output isn't what you want. |
| Ignoring the model's strengths | Don't ask for math — ask it to write code that does math. Don't ask for current events — provide the context yourself. |

## Further Reading

- [AI-Augmented Workflows](AiAugmentedWorkflows) — Applying prompt skills to real work tasks
- [Running Local LLMs](RunningLocalLlms) — Hands-on understanding of how prompts actually work
- [Understanding Generative AI](UnderstandingGenerativeAi) — The mental models behind effective prompting
- [Generative AI Tools for Individuals](GenerativeAiToolsForIndividuals) — Which tools to prompt
- [Generative AI Adoption Guide](GenerativeAiAdoptionGuide) — Hub page

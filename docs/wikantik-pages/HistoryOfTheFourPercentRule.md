# History of the Four Percent Rule

No single number has shaped more retirement decisions than 4%. It appears in every financial planning textbook, every retirement calculator, every FIRE blog. Yet the story of how this number was discovered, popularised, challenged, and ultimately reinterpreted tells us as much about how financial ideas travel as it does about retirement planning itself.

For a practical guide to withdrawal rates and modern alternatives, see [Safe Withdrawal Rates](SafeWithdrawalRates). This article traces the intellectual history.

## The Problem Before Bengen

Before the early 1990s, retirement withdrawal planning was largely guesswork. Financial planners used simple rules of thumb — "spend the interest and dividends," "plan for an 8% average return and withdraw 7%" — that had no empirical foundation. The unstated assumption was that average returns told you everything you needed to know.

The fatal flaw in this thinking would later be named [sequence of returns risk](SafeWithdrawalRates): even if markets average 10% over 30 years, a retiree who happens to retire into a bear market can run out of money while a retiree who retires into a bull market dies wealthy. Average returns are irrelevant to any individual retiree. The order matters.

Nobody had rigorously quantified this problem until a financial planner in Southern California decided to run the numbers.

## 1994: Bengen's Original Paper

William P. Bengen was a former aerospace engineer turned financial planner in El Cajon, California. In October 1994, he published "Determining Withdrawal Rates Using Historical Data" in the *Journal of Financial Planning*. It was, by any measure, a landmark paper — though it took years for the profession to recognise it as such.

### What Bengen Actually Did

Bengen took historical US stock and bond returns from 1926 to 1992 and asked a simple question: for every possible retirement starting year in that dataset, what was the maximum initial withdrawal rate (adjusted annually for inflation) that would have sustained a portfolio for at least 30 years?

He tested every rolling 30-year period: 1926-1955, 1927-1956, 1928-1957, and so on. For each starting year, he found the highest withdrawal rate that didn't exhaust the portfolio.

### The Key Finding

The worst starting years — 1966, 1968, and 1969 — could sustain an initial withdrawal rate of approximately **4.15%** from a portfolio of 50% US large-cap stocks and 50% intermediate-term government bonds. Every other starting year supported a higher rate, often significantly so (6-8% or more for favourable periods like the early 1980s).

Bengen rounded down to 4% for a safety margin and called it "SAFEMAX" — the maximum safe withdrawal rate across the worst historical conditions.

### What Bengen Intended

Critically, Bengen did **not** propose 4% as the ideal withdrawal rate. He proposed it as a **floor** — the worst case. His paper explicitly noted that most retirees could safely spend more, and that a financial planner should use 4% as a starting point, adjusting upward based on the retiree's specific circumstances and market conditions at retirement.

This nuance was almost immediately lost.

## 1998: The Trinity Study

Philip Cooley, Carl Hubbard, and Daniel Walz — three finance professors at Trinity University in San Antonio, Texas — published "Retirement Savings: Choosing a Withdrawal Rate That Is Sustainable" in the *AAII Journal* (American Association of Individual Investors) in February 1998.

### How Trinity Differed from Bengen

The Trinity Study expanded on Bengen's work in several ways:

| Dimension | Bengen (1994) | Trinity (1998) |
|-----------|--------------|----------------|
| Approach | Find the worst-case safe rate | Calculate success probabilities at various rates |
| Output | A single number (SAFEMAX) | Probability tables for multiple rates and time horizons |
| Portfolios | 50/50 stocks/bonds | Multiple allocations (25/75 to 100/0) |
| Time periods | 30 years only | 15, 20, 25, and 30 years |
| Audience | Financial planners | Individual investors (AAII) |

The Trinity professors framed the question probabilistically: "What is the probability of success for a 4% withdrawal rate over 30 years?" Answer: approximately 95% for a 50/50 portfolio.

### Why Trinity Went Viral

Bengen's paper was published in a professional journal read by financial planners. The Trinity Study appeared in a publication read by individual investors. More importantly, it produced neat, quotable probability tables that lent an air of scientific precision to a fundamentally uncertain problem.

"95% success rate" sounds like a near-guarantee. It became the number that launched a thousand retirement calculators.

## 2000s: The [FIRE Movement](FireMovement) Adopts 4%

In the early 2000s, the [Financial Independence / Retire Early](FireMovement) (FIRE) movement began coalescing in online forums and blogs. The 4% rule became its central organising principle, but with a crucial twist: FIRE adherents used it **backwards**.

Bengen and Trinity asked: "Given a portfolio, how much can I safely spend?" FIRE asked: "Given my spending, how large a portfolio do I need?" The answer — annual expenses multiplied by 25 — became the "FIRE number." See [The FIRE Movement](FireMovement) for the full philosophy, variants, and criticisms, and [CoastFIRE](CoastFire) for the variant that front-loads savings and lets compound growth do the rest.

| Annual Spending | FIRE Number (25x) |
|----------------|-------------------|
| $30,000 | $750,000 |
| $40,000 | $1,000,000 |
| $60,000 | $1,500,000 |
| $80,000 | $2,000,000 |

This inversion was mathematically correct but contextually problematic. Bengen's 4% assumed a 30-year retirement. FIRE adherents retiring at 35 or 40 needed their money to last 50-60 years — a horizon Bengen never tested and the Trinity Study never claimed to cover.

See [A Complete Early Retirement Investment Plan](EarlyRetirementInvestmentPlan) for how to adjust for longer horizons.

## The Criticisms Accumulate

By the 2010s, the 4% rule was facing challenges from multiple directions.

### Criticism 1: US Exceptionalism

Bengen and Trinity used US market data exclusively. The US experienced the greatest equity bull market in human history during the 20th century. Researchers who tested the 4% rule with international data found lower safe rates:

| Country/Region | Historical SAFEMAX |
|---------------|-------------------|
| United States | 4.15% |
| United Kingdom | 3.8% |
| Canada | 3.7% |
| Australia | 3.6% |
| Japan | 0.5% (lost decades) |
| Global ex-US average | ~3.5% |

Wade Pfau's 2010 research using data from 17 developed countries showed that the 4% rule would have failed in most countries for various historical periods. The US was the exception, not the rule.

### Criticism 2: Low Yields Change Everything

Bengen's worst-case years (1966-1969) began with bond yields around 5-6% and stock valuations at moderate levels (Shiller CAPE around 20-24). In the 2010s and early 2020s, retirees faced bond yields near zero and elevated stock valuations (CAPE above 30).

Several researchers argued that starting conditions matter and that a retiree entering retirement in a low-yield, high-valuation environment should use a lower initial rate — perhaps 3% to 3.5%. This challenged the 4% rule's implicit claim of being a universal constant.

### Criticism 3: Nobody Spends That Way

The most practical criticism: no real person withdraws a fixed inflation-adjusted amount regardless of market conditions. People respond to circumstances. They spend more in early, active retirement (the "go-go" years), less in their sedentary mid-70s to mid-80s (the "slow-go" years), and potentially much more in late retirement for healthcare (the "no-go" years).

The 4% rule models a robot, not a human being. This insight drove the development of dynamic strategies like [guardrails](GuardrailsSpendingStrategy) and variable percentage withdrawal.

### Criticism 4: It Ignores Other Income

The original research modeled a retiree living entirely off portfolio withdrawals. Most retirees have Social Security, which provides a guaranteed, inflation-adjusted income floor. A retiree whose Social Security covers 50% of expenses needs a much lower portfolio withdrawal rate — making the 4% rule's assumptions unnecessarily pessimistic for many people.

## Bengen's Own Evolution

Bengen continued researching after his 1994 paper. His findings evolved significantly:

**1996-2001**: Bengen refined his analysis, finding that small-cap stocks improved outcomes. A portfolio with 30% large-cap, 20% small-cap, and 50% bonds could sustain a 4.5% initial rate in the worst cases.

**2006**: In updated research, Bengen argued that the 4% rate was too conservative for most retirees. He suggested that planners should adjust upward based on current market valuations — when stocks are cheap (low CAPE ratio), a higher rate is safe; when expensive, be more conservative.

**2012-2020**: In interviews and articles, Bengen expressed frustration that his work had been oversimplified. He repeatedly emphasised that SAFEMAX was a floor, not a target. He noted that in 96% of historical periods, the retiree following the 4% rule would have died with more money than they started with — often much more. The real risk was not destitution but underspending.

**2020**: Bengen publicly revised his SAFEMAX upward to **4.7%** based on expanded data (1871-2020) and the inclusion of small-cap stocks. He argued that 4% was "essentially a worst-case rate" and that most retirees should feel comfortable starting higher.

## How the 4% Rule Is Viewed Today

The financial planning profession's relationship with the 4% rule has undergone a fundamental shift. The number hasn't changed, but its meaning has.

### Then (1994-2010): A Safe Withdrawal Rate

The 4% rule was treated as a **prescription** — the rate you should use. Calculators asked for your portfolio size and returned 4% of it as your annual budget. Financial planners set clients' spending plans around it. The FIRE movement built its entire framework on the 25x multiple.

The implicit message: "4% is the number. Use it."

### Now (2010-present): A Starting Point for a Conversation

Today, the 4% rule is understood as a **crude first approximation** that tells you roughly how much portfolio you need, but says almost nothing about how you should actually spend in retirement. The current consensus:

1. **For planning how much to save**: 4% (or 25x expenses) remains a useful target. It's conservative enough that hitting it means you're almost certainly in good shape.

2. **For actual spending in retirement**: Fixed withdrawal rules have been largely replaced by dynamic strategies — [guardrails](GuardrailsSpendingStrategy), variable percentage withdrawal, the floor-and-upside approach — that respond to actual market conditions and personal circumstances.

3. **The real risk is underspending, not overspending**: This is perhaps the most important shift. Research consistently shows that retirees spend less than they can afford. Median estates among 4% rule followers are enormous. The 4% rule's conservatism, originally a feature, is now recognised as a potential cause of unnecessary deprivation.

4. **Context matters more than the number**: A 65-year-old with $2M in savings and $40K in Social Security is in a completely different position than a 45-year-old FIRE retiree with $1M and no Social Security. The 4% rule treats them identically. Modern planning does not.

5. **Flexibility is the real safety mechanism**: Bengen's original insight — that sequence of returns can devastate a rigid plan — is still valid. But the solution isn't to start with a lower fixed rate. It's to build in rules that adjust spending when conditions change. The [guardrails approach](GuardrailsSpendingStrategy) does exactly this, often allowing a higher initial rate than 4% with lower risk.

## The Legacy

The 4% rule did something remarkable: it transformed retirement planning from folklore to evidence-based analysis. Before Bengen, planners guessed. After Bengen, they calculated. That the calculation was simplified and the nuance was lost in popularisation doesn't diminish the achievement.

Today the 4% rule is best understood not as a rule at all, but as the opening chapter of a still-unfolding research programme. It answered the question "how much is probably safe?" and in doing so revealed the much harder question beneath: "how should I adapt my spending to an uncertain future?" That question drives the modern approaches covered in this cluster.

## Timeline

| Year | Event | Significance |
|------|-------|--------------|
| 1994 | Bengen publishes "Determining Withdrawal Rates Using Historical Data" | Introduces SAFEMAX concept; identifies 4.15% as worst-case safe rate |
| 1998 | Trinity Study published in *AAII Journal* | Probabilistic framing ("95% success"); reaches individual investors |
| ~2004 | FIRE movement begins popularising 25x rule | 4% rule inverted from spending guideline to savings target |
| 2010 | Pfau tests 4% rule with international data | Shows US results are outlier; global SAFEMAX closer to 3.5% |
| 2006-2012 | Guyton-Klinger, Kitces-Pfau publish guardrails research | Dynamic alternatives begin replacing fixed withdrawal rates |
| 2012 | Bengen says 4% was a floor, not a target | Creator pushes back against oversimplification |
| 2015-2020 | Low-yield environment challenges assumptions | Starting conditions recognised as critical input |
| 2020 | Bengen revises SAFEMAX to 4.7% with expanded data | Acknowledges original figure was overly conservative |
| 2020s | Consensus shifts to dynamic strategies | 4% rule becomes planning input, not spending rule |

## Further Reading

- [Safe Withdrawal Rates](SafeWithdrawalRates) — Practical guide to withdrawal rates and modern alternatives
- [Guardrails Spending Strategy](GuardrailsSpendingStrategy) — The leading dynamic alternative to fixed withdrawal rates
- [Retirement Income Blueprint](RetirementIncomeBlueprint) — How withdrawal strategy fits into comprehensive income planning
- [Social Security Claiming Strategy](SocialSecurityClaimingStrategy) — The income floor that changes the 4% rule's math
- [Retirement Planning Guide](RetirementPlanningGuide) — Hub page for the full cluster

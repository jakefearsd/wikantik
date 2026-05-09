---
cluster: operations-research
canonical_id: 01KQ0P44PHST4J295PTSHTA0JK
title: Demand Planning And Sop
type: article
tags:
- supply-chain
- forecasting
- operations
summary: Technical guide to demand forecasting accuracy metrics (MAPE/WMAPE) and the five-step monthly S&OP process.
auto-generated: false
---

# Demand Planning and S&OP: Metrics and the Monthly Drumbeat

Effective Sales and Operations Planning (S&OP) balances unconstrained market demand with constrained supply capacity. Success is measured by forecast accuracy and the rigor of the monthly decision-making cycle.

## 1. Forecast Error Metrics: MAPE vs. WMAPE

Accurate demand planning requires quantifying the gap between the forecast ($\hat{y}$) and actual sales ($y$).

### MAPE (Mean Absolute Percentage Error)
MAPE is the most common metric for communicating error to non-technical stakeholders.
*   **Formula:** $\frac{1}{n} \sum_{i=1}^{n} \left| \frac{y_i - \hat{y}_i}{y_i} \right|$
*   **Limitation:** It is scale-independent, meaning a 10% error on 10 units is treated the same as a 10% error on 1,000,000 units. It also fails if actuals are zero.

### WMAPE (Weighted Mean Absolute Percentage Error)
WMAPE is the industry standard for supply chain optimization because it weights errors by volume.
*   **Formula:** $\frac{\sum |y_i - \hat{y}_i|}{\sum y_i}$
*   **Advantage:** It prioritizes accuracy for "A" items (high-volume/high-value) over "C" items, directly aligning with inventory holding costs and service level targets.

### Forecast Bias
Bias measures the directional tendency of the forecast.
*   **Formula:** $\frac{\sum (y_i - \hat{y}_i)}{\sum y_i}$
*   **Interpretation:** A negative bias indicates persistent over-forecasting (leading to excess inventory), while a positive bias indicates under-forecasting (leading to stockouts).

## 2. The Monthly S&OP Drumbeat (The 5-Step Process)

S&OP is executed as a synchronized monthly cycle, often referred to as the "drumbeat."

### Step 1: Data Gathering (Week 1)
Finalizing the previous month’s actuals, updating inventory positions, and cleaning "noise" from the data (e.g., one-time promotional spikes or stockout-driven suppressed demand).

### Step 2: Demand Planning (Week 2)
Generating the **Unconstrained Demand Signal**.
*   **Statistical Baseline:** Using algorithms (ARIMA, Exponential Smoothing, or ML) to project future demand based on history.
*   **Market Intelligence:** Sales and Marketing layer in "overrides" for upcoming promotions, new product launches (NPI), or competitor activities.

### Step 3: Supply Planning (Week 3)
The supply team assesses the demand signal against **Constraints**.
*   **Capacity Review:** Evaluating labor availability, machine hours, and warehouse space.
*   **Inventory Strategy:** Determining safety stock levels required to meet the demand distribution given lead-time variability.

### Step 4: Pre-S&OP Meeting (Week 4)
A tactical session where Demand and Supply leads resolve minor conflicts.
*   **Scenario Planning:** If supply cannot meet demand, the team prepares "What-If" scenarios (e.g., "If we authorize $50k in overtime, we can meet 95% of the demand").

### Step 5: Executive S&OP Meeting (End of Month)
The final decision-making forum.
*   **Approval:** Executives review the scenarios and sign off on a single "Consensus Plan."
*   **Financial Reconciliation:** Ensuring the operational plan aligns with the quarterly financial forecast and budget.

## 3. Key Success Factors
*   **One Set of Numbers:** All departments must operate from the same consensus plan.
*   **Time Fences:** Defining periods where the plan is "Frozen" (short-term), "Slushy" (mid-term), and "Liquid" (long-term) to prevent disruptive late-stage changes to manufacturing schedules.
*   **Root Cause Analysis:** If WMAPE exceeds targets, the team must perform a 5-Why analysis to determine if the error was due to model failure, bad market intel, or execution lag.

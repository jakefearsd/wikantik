---
cluster: warehouse-automation
canonical_id: 01KQ0P44P1WS7ZYP0CX83XE14M
title: "Cost-Benefit Analysis: NPV and IRR Math"
type: article
tags:
- finance
- economics
- analytics
- decision-making
summary: A mathematical guide to Cost-Benefit Analysis (CBA), specifically detailing Net Present Value (NPV) and Internal Rate of Return (IRR) calculations.
auto-generated: false
date: 2025-01-24
---

# Cost-Benefit Analysis: The Math of Investment

Cost-Benefit Analysis (CBA) provides a quantitative framework for evaluating the viability of projects or investments. For technology and infrastructure projects, the two most critical metrics are **Net Present Value (NPV)** and **Internal Rate of Return (IRR)**.

## 1. Net Present Value (NPV): The Time Value of Money

NPV calculates the difference between the present value of cash inflows and the present value of cash outflows over a specific period. It accounts for the fact that a dollar today is worth more than a dollar tomorrow due to its potential earning capacity (the "Discount Rate").

### The NPV Formula
$$NPV = \sum_{t=0}^{n} \frac{R_t}{(1+i)^t}$$

Where:
*   $R_t$: Net cash flow (inflow - outflow) during a single period $t$.
*   $i$: The **Discount Rate** or Hurdle Rate (usually the company's Weighted Average Cost of Capital, WACC).
*   $t$: The time period (usually years).
*   $n$: The total number of periods (project lifespan).

### Decision Rules
*   **NPV > 0:** The project adds value and should be accepted.
*   **NPV < 0:** The project will result in a net loss and should be rejected.
*   **NPV = 0:** The project breaks even; the decision depends on non-financial factors.

## 2. Internal Rate of Return (IRR)

The IRR is the discount rate that makes the NPV of all cash flows from a particular project equal to zero. In essence, it is the expected compound annual rate of return that will be earned on a project or investment.

### The IRR Calculation
The IRR is the value of $i$ that satisfies:
$$0 = \sum_{t=0}^{n} \frac{R_t}{(1+IRR)^t}$$

Unlike NPV, IRR is expressed as a percentage. It is usually calculated via iterative numerical methods (like the Newton-Raphson method) as there is no analytical solution for $n > 2$.

### Decision Rule
*   **IRR > Hurdle Rate:** Accept the project.
*   **IRR < Hurdle Rate:** Reject the project.

## 3. Comparing NPV and IRR

While both metrics are useful, they can lead to different conclusions for mutually exclusive projects.

| Feature | Net Present Value (NPV) | Internal Rate of Return (IRR) |
| :--- | :--- | :--- |
| **Unit of Measure** | Currency (Absolute Value) | Percentage (Relative Value) |
| **Reinvestment Assumption** | Assumes reinvestment at the Discount Rate. | Assumes reinvestment at the IRR (can be unrealistic). |
| **Project Scale** | Favors larger projects with higher absolute returns. | Favors smaller projects with high efficiency/yield. |
| **Complexity** | Straightforward to calculate. | Can have multiple solutions for non-conventional cash flows. |

**Technical Tip:** For most corporate decisions, NPV is considered the superior metric because it correctly models the cost of capital and accounts for the total value added to the firm.

## 4. Application: Technology R&D Justification

When justifying a new technology (e.g., an automated sorting system):
1.  **Estimate Initial Outlay ($R_0$):** Hardware, software licenses, installation, and training costs (usually a negative number).
2.  **Forecast Annual Savings ($R_1 \dots R_n$):** Reduced labor costs, lower error rates, and increased throughput.
3.  **Select Discount Rate ($i$):** Use a higher rate (e.g., 12-15%) for risky R&D and a lower rate (e.g., 8-10%) for established infrastructure.
4.  **Run Sensitivity Analysis:** Vary $R_t$ and $i$ by $\pm 10\%$ to see how robust the NPV is to market or operational fluctuations.

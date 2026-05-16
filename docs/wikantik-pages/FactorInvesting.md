---
cluster: index-fund-investing
canonical_id: 01KQ0P44QA6NCWP8RHD6SXPJ2Y
title: Factor Investing
type: article
tags:
- factor-investing
- quantitative-finance
- fama-french
- smart-beta
status: active
date: 2025-05-15
summary: A rigorous examination of factor-based risk premia, Fama-French models, and the math of Value, Size, and Momentum premiums.
auto-generated: false
---

# Factor Investing: Quantitative Risk Premia

Factor investing moves beyond the Capital Asset Pricing Model (CAPM) by identifying persistent drivers of return that explain the cross-section of equity returns. It treats asset classes as bundles of underlying risk factors.

## 1. Beyond CAPM: The Multi-Factor Framework

The classic CAPM assumes returns are driven solely by market beta ($\beta$):$$E[R_i] = R_f + \beta_i(E[R_m] - R_f)$$Factor investing utilizes the **Arbitrage Pricing Theory (APT)** framework, positing that returns are a linear function of multiple factors:$$E[R_i] = R_f + \sum \beta_{i,j} \lambda_j$$Where$\beta_{i,j}$is the exposure (loading) to factor$j$, and$\lambda_j$is the risk premium for that factor.

## 2. The Fama-French 5-Factor Model

The foundational model for factor analysis is the Fama-French 5-Factor Model, which expands the original 3-factor model to include profitability and investment:$$R_{it} - R_{ft} = \alpha_i + \beta_{i1}(R_{mt} - R_{ft}) + \beta_{i2}SMB_t + \beta_{i3}HML_t + \beta_{i4}RMW_t + \beta_{i5}CMA_t + \epsilon_{it}$$*   **Market ($R_m - R_f$):** Equity risk premium.
*   **SMB (Small Minus Big):** The Size premium. Small-cap stocks tend to outperform large-cap stocks over long horizons.
*   **HML (High Minus Low):** The Value premium. Stocks with high book-to-market ratios (Value) outperform those with low ratios (Growth).
*   **RMW (Robust Minus Weak):** Profitability factor. Firms with high operating profitability perform better.
*   **CMA (Conservative Minus Aggressive):** Investment factor. Firms that invest conservatively outperform those with aggressive investment growth.

## 3. Key Factor Premiums and Math

### 3.1 Value (HML)
Value is a "cheapness" factor. It exploits behavioral biases (overreaction) and risk premia (distress risk).
*   **Proxies:** P/B, P/E, EV/EBITDA, Cash Flow/Price.
*   **The Math:** Portfolio is constructed by longing high B/M stocks and shorting low B/M stocks.

### 3.2 Momentum (WML)
The tendency for assets that have performed well in the recent past (3–12 months) to continue performing well.
*   **Calculation:** 12-1 Momentum (Returns from month -12 to -2, excluding the most recent month to avoid short-term reversal).
*   **Persistence:** Attributed to underreaction to news and herding behavior.

### 3.3 Quality and Volatility
*   **Quality:** Focuses on low debt, stable earnings growth, and high margins.
*   **Low Volatility:** The "Low Vol Anomaly"—low beta/low volatility stocks often provide higher risk-adjusted returns than high-risk stocks, contradicting basic CAPM.

## 4. Implementation: Smart Beta and Multi-Factor

Experts don't just "buy value"; they optimize for **Factor Interaction**.
*   **Top-Down:** Weighting individual factor indices.
*   **Bottom-Up (Preferred):** Scoring each security across multiple factors simultaneously to find stocks that provide "Value + Quality" exposure, reducing the risk of "Value Traps."

### 4.1 Rebalancing and Turnover
Factor premiums are not static. High-turnover factors like Momentum require careful transaction cost modeling.$$Net\ Return = \sum (w_i \cdot r_i) - \text{Trading Costs} - \text{Market Impact}$$
## 5. Summary of Factor Premiums

| Factor | Description | Theoretical Driver |
| :--- | :--- | :--- |
| **Value** | Cheap relative to fundamentals | Distress risk / Behavioral overreaction |
| **Size** | Small market capitalization | Liquidity risk / Information asymmetry |
| **Momentum** | Trend persistence | Herding / Underreaction to news |
| **Quality** | Strong balance sheets/earnings | Barrier to entry / Management quality |
| **Low Vol** | Lower idiosyncratic risk | Leverage constraints / Lottery preference |

Factor investing requires a disciplined, rules-based approach to capture these premia while managing the significant periods of underperformance (cyclicality) inherent in any single factor.

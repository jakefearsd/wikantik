---
canonical_id: 01KQ0P44XA408GF7WX6V7N1832
title: Tax Loss Harvesting
type: article
cluster: index-fund-investing
status: active
date: '2026-04-26'
summary: How tax-loss harvesting works, the wash-sale rule that catches most retail
  investors, the cases where it actually pays meaningful money, and the systematic
  approach that captures the benefit without the common pitfalls.
tags:
- tax-loss-harvesting
- TLH
- taxes
- index-funds
- wash-sale
related:
- LowCostIndexFundInvesting
- TaxPlanningFundamentals
- IndexFundPortfolioConstruction
- RoboAdvisorComparison
- RebalancingStrategies
hubs:
- LowCostIndexFundInvesting Hub
---
# Tax Loss Harvesting

Tax-loss harvesting (TLH) is the practice of selling investments at a loss to capture the tax benefit, while maintaining market exposure by buying a similar (but not "substantially identical") replacement. Done well, it can save 0.50–1.50% per year in taxes for investors with taxable accounts in higher tax brackets — meaningful over decades.

Done poorly, it triggers the wash-sale rule, complicates tax preparation, and produces suboptimal portfolio outcomes. This page is about how to do it correctly, the situations where it actually pays, and the common errors that defeat the strategy.

## How it works mechanically

A taxable brokerage account holds a position that has fallen below its cost basis. You sell the position to "realize" the loss for tax purposes. You immediately buy a similar but not "substantially identical" replacement to maintain market exposure. The realized loss offsets capital gains and (up to $3,000/year) ordinary income.

A simple example:

- You bought $50,000 of VTI at $200/share = 250 shares
- VTI now trades at $170/share = current value $42,500
- You have a $7,500 unrealized loss
- You sell VTI and immediately buy ITOT (a similar total-market ETF) at $170 equivalent
- You have realized a $7,500 loss for tax purposes; market exposure is unchanged
- The $7,500 loss can offset capital gains in the current year + carry forward indefinitely; up to $3,000/year can also offset ordinary income

The replacement (ITOT vs. VTI) tracks a similar index but is from a different provider with different underlying methodology — enough difference to satisfy the IRS that they are not "substantially identical."

## The wash-sale rule

The IRS prevents people from claiming a loss while keeping the same position. The wash-sale rule:

> If you sell a security at a loss and buy a "substantially identical" security within 30 days before or 30 days after the sale, the loss is disallowed.

The tricky parts:

### What is "substantially identical"

The IRS has not given precise guidance. Generally accepted as substantially identical:

- The same security from the same fund family (VTI selling and buying VTI back within 30 days)
- ETFs and mutual funds tracking the *exact same index* from different providers (some debate; conservative practice avoids this)

Generally accepted as NOT substantially identical:

- ETFs tracking similar but different indexes (VTI = CRSP US Total Market, ITOT = S&P Total Market — different underlying indexes)
- Same asset class but different fund families (VTI and SCHB)
- Different style or factor (VTI to VOO — total market vs. S&P 500 are similar but different)

### The 30-day windows

The wash-sale period is 30 days *before* and 30 days *after* the sale date — a 61-day window total. Buying the same security at any point in those 60 days disallows the loss.

### Cross-account application

The wash-sale rule applies across accounts, including spouse's accounts. Buying VTI in your IRA while selling VTI for a loss in your brokerage triggers wash sale. (And worse: the disallowed loss in this case becomes permanent; it does not transfer to the IRA.)

### Auto-investment in 401(k)s

If your 401(k) holds a fund that automatically purchases each paycheck, and you tax-loss harvest a similar fund in your taxable account, you may inadvertently trigger wash sales. Disable 401(k) contributions or carefully select non-overlapping funds during TLH activity.

## Pairing strategy

The standard approach uses pairs of similar but distinct funds. You hold one; sell at a loss to harvest; buy the other; later swap back if/when desired.

Common pairs:

| Asset class | Pair A | Pair B |
|-------------|--------|--------|
| Total US market | VTI | ITOT |
| S&P 500 | VOO | IVV (or SPLG) |
| Total international | VXUS | IXUS |
| Total US bond | BND | AGG (or SCHZ) |
| Emerging markets | VWO | IEMG |
| US small-cap value | AVUV | DFSV |

Note: VTI and VOO are *not* a good pair (one is total market, one is large-cap-only — different exposures, not similar). Pair within the same asset class, not across.

## When TLH actually pays

The benefit varies dramatically based on circumstances:

### Strong cases

- **High tax bracket** (32%+ federal plus state): each $1,000 of harvested losses is worth $400+
- **Large taxable account** ($100K+): more positions, more opportunities
- **High volatility** (frequent drawdowns provide harvesting opportunities)
- **Plan to hold long-term** (the deferred gains compound)
- **You will use the losses** (against current or future capital gains)

### Weak cases

- **Low tax bracket** (12% or 0% on long-term gains): each $1,000 of losses is worth $0–$120
- **Small taxable account** ($10K range): limited opportunities, complexity not worth it
- **Account in tax-deferred or Roth**: no benefit; TLH is meaningless
- **Plan to sell soon** (defeats the purpose; you are realizing gains anyway)
- **No expectation of capital gains to offset**: $3,000/year cap on ordinary income offset

For a typical taxable investor in the 24% federal bracket with $200K in a brokerage, TLH might save $1,500–$3,000/year in real tax. Worth the effort but not a primary investment driver.

## When automation helps

Robo-advisors do TLH systematically. Wealthfront, Betterment, and others scan accounts daily for harvesting opportunities and execute automatically. The standard fee (0.25% AUM) is often justified primarily by TLH alone for taxable accounts in higher brackets.

DIY TLH at smaller scale: 2–4 times per year is reasonable. Look for harvesting opportunities during major market drops; ignore minor fluctuations.

## A specific TLH workflow

A practical approach for self-managed taxable accounts:

### Setup

1. Hold paired-fund options for each asset class (e.g., VTI as primary; know ITOT as the swap target)
2. Disable automatic dividend reinvestment for the funds you might harvest (DRIPs can trigger wash sales)
3. Track cost basis carefully (your brokerage does this; verify it is correct)

### Harvesting

1. After a market drop of 5%+, check each holding for unrealized losses
2. For positions with meaningful losses (say, $1,000+), execute the swap:
   - Sell the loss position
   - Immediately (same day, ideally same minute) buy the paired fund
3. Document the trade: original cost basis, sale price, replacement purchase
4. Mark calendar: do not buy back the original fund for 31+ days

### Reconciliation

1. After 31 days, you can swap back if desired (though it is often unnecessary)
2. At year-end, verify the broker's reported losses match your records
3. Use the losses on Schedule D / Form 8949

## Common failure patterns

- **Triggering wash sales via 401(k) auto-purchases.** A common error; the IRS does not care that it was unintentional.
- **Buying back the same fund within 30 days.** Same mistake; usually inadvertent.
- **TLH in tax-deferred accounts.** The losses cannot be used; the activity is wasted.
- **Excessive harvesting on small fluctuations.** Costs (bid-ask spread, complexity, broker friction) exceed savings.
- **Selecting a "substantially identical" replacement.** Risk of disallowed loss; conservatively use clearly-different funds.
- **TLH in a year you have no gains.** $3,000/year cap on ordinary income offset; excess carries forward.
- **Ignoring state taxes.** Federal TLH is the focus; some states do not conform to federal cost-basis rules. Conservative state-by-state approach.

## What TLH does not do

- Improve pre-tax returns (it changes timing of taxes, not pre-tax investment outcomes)
- Justify trading frequently (the benefit is bounded; over-trading hurts more than it helps)
- Eliminate the eventual gain (deferred is not eliminated; eventually you sell at the lower basis and pay tax)
- Substitute for asset allocation discipline (TLH is an optimization on top of a sound portfolio, not a strategy on its own)

## Further Reading

- [LowCostIndexFundInvesting](LowCostIndexFundInvesting) — The investment philosophy
- [TaxPlanningFundamentals](TaxPlanningFundamentals) — Tax framework
- [IndexFundPortfolioConstruction](IndexFundPortfolioConstruction) — Building portfolios with TLH-friendly structures
- [RoboAdvisorComparison](RoboAdvisorComparison) — Automated TLH
- [RebalancingStrategies](RebalancingStrategies) — TLH in coordination with rebalancing
- [LowCostIndexFundInvesting Hub](LowCostIndexFundInvesting+Hub) — Cluster index

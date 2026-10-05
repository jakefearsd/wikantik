# Production formula-rendering audit — 2026-06-12

Pass over **wiki.wikantik.com** checking math/LaTeX rendering across all 450 math-bearing
pages (of 1195 total), driven by the page-render endpoint + the admin MCP write surface.

## Summary

| | Count |
|---|---|
| Math pages scanned | 450 |
| Pages with broken math rendering (detected) | 156 |
| **Fixed & verified (now render clean)** | **156 (all)** |
| Residual defects outstanding | 0 |
| Currency-`$` pages reviewed, render as literal `$` (acceptable) | 117 |

> **Resolution (2026-06-12, same pass):** the 17 residual were all fixed. Root causes were
> (a) literal currency/`$` parsed as math on finance pages, and (b) display `$$…$$` glued
> mid-line to prose. Fix applied to git source + pushed via MCP:
> 1. **Currency-safe escape** — `(?<!\$)\$(?!\$)(?=\d)` → `\$` (escapes `$`+digit, protects
>    `$$` display delimiters and code fences). Also escaped the `($)` "in dollars" notation.
> 2. **Isolate all display `$$…$$`** onto their own lines (`\$\$(.+?)\$\$` → blank/`$$`/content/`$$`/blank),
>    which also restores the inverted inline-`$` parity downstream (no inline re-spacing needed).
> All 17 re-verified: `math-display` present, no prose-in-math. **`TextFormattingRules`** (the
> scan fetch-error) is **not a defect** — it's the formatting-docs page; its `$`/`$$` are
> examples inside code spans, and the browser path (`/wiki/TextFormattingRules`) returns 200.
> The API `?render=true` path 403'd via Cloudflare WAF — a separate WAF observation, not math.

## Root cause

Two compounding issues, both producing the same symptom — display `$$…$$` blocks rendering
as inline, which inverts inline-`$` parity so prose downstream gets wrapped in `math-inline`
spans while real LaTeX leaks out as plain text:

1. **Stale production content.** The prod page store persists across deploys, so corpus math
   fixes that landed in git (commits `41b26a1212`, `ba1dc4687a`) never reached prod. Most
   broken pages had the pre-fix content on prod while git was already correct.
2. **A source defect git itself missed.** 38 pages had display math written on a *single line*
   (`$$ MATH $$`), which `DisplayMathPreProcessor` (requires `$$` alone on its own line)
   cannot detect. The earlier "102-page" fix addressed *inline-glued* display math but not
   *standalone single-line* `$$…$$`.

## Fixes applied (138 pages)

- **Re-synced git-canonical content to prod via MCP `update_page`** for pages where git was
  already correct but prod was stale (~100 pages). Proven on `FastenerEngineering` first.
- **Isolated single-line `$$…$$` → own-line blocks** in 38 git source files
  (deterministic transform, math content untouched), committed and pushed to prod.
- Every fixed page re-verified: `math-display` spans present, no leaked `$`, no prose-in-math.

### Source files corrected (committed this pass)
ActorModelProgramming, AiGovernanceFrameworks, BackdoorRothStrategies, BearingMechanics, ChaosDynamical, CloudRoiFramework, CombinatoricsRefresher, ComplexAnalysis, CounterfeitDetectionPhysics, DifferentialCalculus, DifferentialGeometry, EvaluatingRetrievalQuality, FeatureEngineering, FuzzyLogic, GraphRerankStep, HighAvailability, IndustrialSearchSystems, InventoryTheory, LinearAlgebra, LlmEvaluationMetrics, MachineLearning, MarketRecoveryCoefficients, MarkovChainFundamentals, ModalLogic, NaturalLanguageProcessing, OperationsResearch, PredicateLogic, ProbabilityTheory, RateLimitingAndThrottling, RecommendationSystems, SixSigmaMethodology, TemporalLogic, TensorTheory, TextAnalysisWithDataScience, Topology, VehicleRoutingProblem, WarehouseLaborManagement, WarehouseSafetyAndErgonomics

## Residual defects — FOLLOW-UP NEEDED (17)

### Currency `$` parsed as math (13)
Finance pages where literal money (`$3,800`, `$500K`, `$50K/year`) pairs up as inline-math
delimiters and wraps prose. **Recommended fix:** escape currency dollar signs (`$` → `\$`)
on these pages — needs per-page judgment to avoid escaping genuine inline math.

- `EconomicHistoryOfMetallurgicalCycles` — e.g. `3,800/oz** and silver at **`
- `FirstJobFinancialChecklist` — e.g. `13,780 plus market growth. At 7% real returns over`
- `InventoryManagementStrategies` — e.g. `&#61; Holding or Carrying Cost per unit per year (`
- `LongRunningProjects` — e.g. `3.  **Backward Pass:** Calculate the **Latest Star`
- `LongTermCareInsurance` — e.g. `500K for potential LTC. At 5% growth, that becomes`
- `MortgageStrategies` — e.g. `10K&#43; lump-sum payment, with a small fee (~`
- `NetWorthTracking` — e.g. `300,000 portfolio with a 2% monthly market move (`
- `RetirementIncomeBlueprint` — e.g. `50K/year at 12% while taxable income is low. Lisa `
- `RetirementWithdrawalSequencing` — e.g. `66,450 to Roth at the 12% rate (`
- `SideIncomeStrategies` — e.g. `400 of self-employment income are subject to self-`
- `TaxPlanningForRetirementAccountWithdrawals` — e.g. `97,000/year from Traditional to Roth IRA, filling `
- `TypesofInvestmentAccountsTutorial` — e.g. `18,000 per beneficiary per year (2025) count again`
- `VariablePercentageWithdrawal` — e.g. `40k/yr) ensures basic needs are met, while a ceili`

### Complex inline-parity cases (4)
Display math renders, but a residual stray/mismatched inline `$` still wraps one prose span.
Need per-page inspection.

- `GroupTheorySymmetry` — e.g. `When this occurs, the physical quantity associated`
- `LamportClocks` — e.g. `### 3.2 Comparison and Concurrency DetectionFor tw`
- `NetworkOptimization` — e.g. `is the cost from start and`
- `RotationalDynamics` — e.g. `is the moment of inertia about the center of mass `

### Scan fetch error (2)
- `TextAnalysisWithDataScience` — render endpoint failed during scan; re-check manually.
- `TextFormattingRules` — render endpoint failed during scan; re-check manually.

## Reviewed, NOT defects

- **117 pages** flagged only for leaked `$` (no prose-in-math, no missing display):
  these are currency dollar signs that render as literal `$` text — acceptable. Listed for a
  possible future hardening pass (escape all currency `$` corpus-wide). Pages:
  AIModelTraining, AccountTypeStrategy, AcidTransactionsAndIsolation, AgentLoops, AgenticArchitecture, AiDrivenRetirementPlanning, AnnuitiesVsSystematicWithdrawals, ApiDesignBestPractices, AssetAllocationGuide, BasicsOfCompoundInterest, BehavioralFinanceForInvestors, BlockchainProvenance, BondLaddersForRetirementIncome, BucketStrategyForRetirement, BudgetingMethods, CQRSAndEventSourcing, CalculatingYourFiNumber, CalculusRefreshForCS, CharitableGivingInRetirement, CoastFire, CompoundInterestAndTaxAdvantagedAccounts, CompoundingIntuition, ConditionalResidenceAndI751, CreditScoreOptimization, DebtPayoffStrategies, DemandPlanningAndSop, DevelopingWithPostgresql, DividendVsTotalReturnInvesting, DollarCostAveraging, DownsizingInRetirement, DualCitizenshipConsiderations, EarlyRetirementInvestmentPlan, EditFindAndReplaceHelp, EmbeddedAiOnLimitedHardware, EnergySecurityGeopolitics, EstatePlanningForRetirees, EuRetirementSavingsGuide, ExpenseRatioDeepDive, ExpenseRatiosAndTheirEffectOnCompounding, FinancialResilience, FireMovement, FiveTwentyNinePlansAndEducationSavings, GradientDescentAndOptimizers, GuardrailsSpendingStrategy, GulfWarMarkets, HealthSavingsAccounts, HistoryOfTheFourPercentRule, HomeBuyingProcess, IBondsAndTreasuries, IdentityTheftProtection, ImmigrationFinancialRequirements, ImmigrationProcessingTimelines, IndexFundPortfolioConstruction, InflationProtectionStrategies, InsuranceTypesAndCoverage, IntroductionToIndexFundsAndETFs, InvestingInYourTwenties, InvestmentPolicyStatement, IranWar2026EconomicImpact, KnowledgeGraphExtractionBenchmarks, LLMFineTuning, LaserCuttersAndEngraversForWood, LifeInsuranceTypes, LinearProgrammingFoundations, LinuxCommandLineEssentials, LinuxCommands, LinuxShellScriptingFundamentals, LlmTokenEconomicsAndPricing, LoggingConfig, LowCostIndexFundInvesting, MaximizingRetirementAccountContributions, MedicarePlanningAndHealthcare, MilitaryRetirementBenefits, ModernPrepper, MonteCarloRetirementPlanning, MutualFundVsEtfComparison, OutboxPattern, PostgreSQLLocalDeployment, PreMedicareBridgeStrategies, PromptCaching, PythonDeployment, PythonLanguage, ReactiveProgramming, RealEstateInvestingBasics, RequiredMinimumDistributions, RequirementsGathering, RetirementPlanningForTheSelfEmployed, RetirementPlanningForWomen, RetirementRelocationAnalysis, RetirementSpendingPatterns, RetrievalExperimentHarness, RevenueManagementWithOR, RoboAdvisorComparison, RothConversionLadder, RothConversionStrategy, RuleOf72AndInvestmentGrowthCalculations, RussiaUkraineWarMilitaryTechnology, SafeWithdrawalRates, SecureActRetirementChanges, SequenceOfReturnsRisk, SmartContractsForAutonomousAgents, SocialSecurityClaimingStrategy, SupplyChainAndLogisticsOptimization, TaxBenefitsOfRetirementAccounts, TaxLossHarvesting, TaxPlanningFundamentals, ThreeDeePrintingMeetsWoodworking, TradeRoutes, USTaxTreatiesWithEuropeanCountries, UnderstandingRiskTolerance, UserStoryWriting, WarOnTerrorMarkets, WikantikOnDocker, WillsAndTrusts, WoodworkingJoineryTechniques, WorkAuthorizationAndEad, WorldCoinsForBeginners

## Latent product issue (not content)

`MathValidationPageFilter` (shipped 2.0.16) did **not** flag any of these as ERROR — the
single-line-`$$` and currency-`$` rendering defects pass the linter. Worth a follow-up rule
so the save-time gate catches them going forward.

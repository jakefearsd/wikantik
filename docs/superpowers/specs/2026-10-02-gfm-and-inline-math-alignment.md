# GFM strikethrough/task lists, one inline-math rule, lazy KaTeX — decisions and corpus audit

Date: 2026-10-02. Follow-ups 1–3 of the Obsidian adoption program (native wikilinks / vault import / live preview).

## Problem

The three renderers disagreed:

| Construct | Published page (flexmark) | Editor side preview (remark) | Live preview (CodeMirror) |
|---|---|---|---|
| `~~strike~~` | literal tildes | strikethrough | strikethrough |
| `~strike~` (single) | literal | strikethrough (remark-gfm default) | literal |
| `- [ ] task` | literal `[ ]` | checkbox | checkbox |
| `costs $5 and $10` | literal | **math** | literal |
| `$c_o = \$1.00$` | math `c_o = \` + junk | math | math `c_o = \` + junk |

KaTeX (~84 kB gzip JS+CSS) also loaded on every page view, math or not.

## Decisions

1. **Strikethrough is double-tilde only** on every surface (flexmark `StrikethroughExtension`, remark-gfm
   `singleTilde: false`). This matches Obsidian and Pandoc; GitHub also accepts a single tilde, but prose such
   as "~5 min to ~10 min" would strike through, so we follow Obsidian. GFM flanking rules apply: `~~ a ~~` and
   a lone `~~` stay literal.
2. **Task lists follow GitHub/GFM output**: `<li class="task-list-item">` with a read-only
   `<input type="checkbox" class="task-list-item-checkbox" disabled>` (`checked` for `[x]`/`[X]`). They are not
   clickable on the published page, as in the GFM spec output; editing stays in the editor.
3. **Inline math uses the Pandoc `tex_math_dollars` rule** (which Obsidian and most Markdown math tools follow), on
   the server (`InlineMathRule`, shared by `InlineMathParser` and the save-time `MathSpanExtractor`), in live
   preview and in the editor preview (`inlineMath.js` + `remarkInlineMath` micromark construct):
   the opening `$` is not followed by whitespace; the closing `$` is not preceded by whitespace and **not followed by a
   digit**; `\$` inside math is an escaped dollar, not a closer; `$$` never opens inline math. Hence
   `$20,000 and $30,000` is plain text and `$c_o = \$1.00$` is one formula.
4. **KaTeX loads lazily** in the reading view: only when the rendered page contains `.math-inline`/`.math-display`.

## Corpus audit (what was checked, and the verdict)

Both corpora were scanned with a simulator of the old and new rules, after excluding front matter, fenced/indented
code and inline code: the repo mirror `docs/wikantik-pages/` (1,202 pages) and production (1,381 pages; raw files
via `bin/remote.sh pages-pull`, plus 190 container-owned pages via the public `?format=md` endpoint).

- **Strikethrough:** three `~~` uses. `ScimProvisioningDesign` `~~`/scim/v2/Groups`~~` is an intended
  strikethrough and now renders as one. `SearchPageHelp` `| ~~ | fuzzy |` is a lone operator and stays literal.
  No edits needed.
- **Task lists:** 143 repo + 156 prod `- [ ]`/`- [x]` lines, all checklists. They now render as checkboxes, which
  is their intent. No edits needed.
- **Inline math, closing `$` followed by a digit / `\$` inside math:** 275 paragraphs render differently.
  - 210 are currency the old rule captured as math (e.g. `$100,000 | $2,500,000 | \$452,000` rendered a KaTeX
    span). Under the new rule they are plain text, which is their intent. No edits.
  - The rest were reviewed by hand. Formulas such as `$W_a = \$500,000 \times (1.068)^{30} \approx \$3.59M$`,
    `$c_o = \$1.00$` and `$\$10$` were broken by the old rule and are correct under the new one. No edits.
  - Pages whose intent needed a source repair were edited so they render the same under the old and the new rule
    (prod still runs the old rule until release):
    - `$\times$6` → `$\times$ 6`
    - `\$3^7$` → `$3^7$` (an escaped opening dollar left by the 2026-06-20 currency-escaping pass)
    - currency inside a formula → prose (`\$2,500,000 / \$488,000 ≈ 5.1 years`)
- **LaTeX decoded into control characters (prod only):** 26 pages contain `\text`, `\times`, `\frac`, `\theta`,
  `\beta`, `\bowtie` and similar whose backslash escape was decoded into TAB/form-feed/backspace (JSON `\t`/`\f`/`\b`), and
  `LagrangianMultipliers` has `\nabla` split by a newline. Restored deterministically (`fix_ctrl.py`) and checked by diff — 13 of the 26 plus
  LagrangianMultipliers so far; the other 13 are listed under "Not yet applied".

Repo edits: commit `4bd95b6681` (CalculusRefreshForCS, GroupTheorySymmetry, PulleySystems, SprintPlanning,
WarehouseAutomationReturnOnInvestment, WoodworkingJoineryTechniques). Production edits: see "Production edits" below.

## Production edits

Applied 2026-10-02 through the admin MCP `update_page` (optimistic lock). Every edit was checked line by line to render
identically under the old and the new rule, then verified by re-pulling prod and diffing against the pre-edit snapshot
(front matter unchanged on every page; edited pages are now stored with LF line endings).

| Page | Repair |
|---|---|
| AmortizedAnalysis | 011...1, 100...0, 2n escaped-dollar -> math + glue spacing |
| AnnuitiesVsSystematicWithdrawals | \\$ -> \$ throughout; $30,000 / 1,120,000 = 2.68\%$ math -> prose |
| AppliedMathSurvey | \$10^{12}$ -> $10^{12}$ in table |
| BerlinInTheWeimarRepublic | restored \text |
| BlockchainMathematics | 2^256, 2^128, 3.4e38 fixed; eaten closing dollar/newline before list item 3 restored |
| CheeseProduction | restored \alpha \beta \text |
| ClockSynchronization | 500ppm and 1ms-50ms table cell -> inline math |
| CloudDisasterRecovery | restored \times \text \approx |
| ColdChainLogistics | fixed 5 escaped-opening-dollar temperature spans (table + prose) |
| ColdChainSensorNetworks | sim 100m -> $\sim$ 100 m |
| CombinatoricsRefresher | fixed 4 escaped-opening-dollar spans ($2^n$, 26^8, 62^8, 95^8) |
| ConsistentHashing | restored \text \frac \approx \times |
| ConvexOptimization | restored \forall \theta \to \text |
| CounterfeitDetectionPhysics | restored \alpha \text \frac \rho |
| DatabaseSharding | fixed 4 escaped-opening-dollar spans ($0$, $2^{n}-1$, $1/N$ x2) |
| DovetailJointMethods | fixed 4 escaped-opening-dollar math spans in rake angle line |
| EntityResolutionTechniques | \$1000\times$ -> $1000\times$ |
| EvaluatingRetrievalQuality | restored \text \frac |
| FineTuningLargeLanguageModels | restored \times \frac \alpha |
| FormalVerificationDistributed | restored \text \triangleq |
| FuzzyLogic | \$2^n$ -> $2^n$ |
| GameDayExercises | restored \text |
| GroupTheorySymmetry | escaped-dollar-before-math (8!, 3^7, 0 mod 3, 12!, 2^11) -> inline math |
| InferenceServing | restored \text \approx \frac \times |
| InfinityMathematics | 1, 2^{aleph_0} (x3) -> inline math + glue spacing |
| InformationTheory | spaced glued inline math, \$2^{H(X)}$ fixed, split glued heading/list; table rows untouched |
| InterpretingHybridRetrievalMetrics | restored \text \frac \approx |
| JvmTuning | restored \text |
| LagrangianMultipliers | restored newline-decoded nabla (2 places) |
| OkrsAndGoalSetting | Sweet Spot 0.7/1.0/0.3 -> inline math (YAML code-block \$ left) |
| PerishableVehicleRouting | 4 deg C, 20 deg C, 5% -> inline math |
| PhiAccrualFailureDetector | spaced glued math/words; rejoined split heading; \$10^{-8}$ fixed |
| PropositionalLogic | unescaped \$1$ \$0$ \$2^n$ opening dollars |
| SixSigmaMethodology | \$6\sigma$ -> $6\sigma$ |
| VariablePercentageWithdrawal | currency-as-math -> prose escaped-dollar amounts |
| WarehouseAutomationReturnOnInvestment | savings/payback currency math -> prose |
| SprintPlanning | `$\times$6` → `$\times$ 6` (2 lines) |

Checked and left unchanged (false positives): NetworkSecurityFundamentals, WikantikOnDocker (`\${VAR}` in shell code).

**Not yet applied** (the session's permission classifier blocked the batch; the same mechanical `fix_ctrl.py` repair
is still owed): KnowledgeGraphConstructionPipeline, LeanManufacturingPrinciplesHub, LinearProgrammingSimplex, MarketRecoveryCoefficients, ModernPrepper, PacelcTheorem, PulleySystems, RelationalDatabaseFundamentals, RetrievalAugmentedGeneration, RiskManagement, SelfSovereignIdentity, StochasticProcesses, TokenBudgeting.

## Round 2 — formatting damage left by past automated passes

Re-spacing of glued inline math (`flip$k$bits` → `flip $k$ bits`, `**Size:**$x$` → `**Size:** $x$`; plural/ordinal
suffixes such as `$n$th` stay glued), list items and headings run into the previous line (`Where:*$S_t$: …` → a
`Where:` line plus bullets, `…text.## Heading` → its own heading), plus the control-character repair — one
deterministic pipeline (`fix_ctrl` → `fix_struct` → `fix_glue`) that never touches code or `$$` blocks; every changed
line was checked to give identical math under the old and the new rule.

- Repo mirror: 102 pages, commit `e83545e804` (one table line in VariablePercentageWithdrawal repaired by hand).
- Production: **not yet applied** — the session's permission classifier blocked the prod batch. Prepared and checked:
  87 pages for the pipeline (this includes the 13 control-character pages above) and 7 for a manual pass
  (RateLimitingAndThrottling, EconomicHistoryOfMetallurgicalCycles, NumberTheory, RealAnalysis, WoodworkingJoineryTechniques, DurableVsPerishableOptimization, RoadmapPackagingAndPricing).

### Round 2 on production (2026-10-03)

- **Applied and verified (63 pages):** after re-pulling prod, each matches the corrected pipeline's output exactly, with front
  matter unchanged. The edited pages are now stored with LF line endings. Hand repairs applied: RateLimitingAndThrottling,
  EconomicHistoryOfMetallurgicalCycles, NumberTheory, RealAnalysis, WoodworkingJoineryTechniques, DurableVsPerishableOptimization
  (RoadmapPackagingAndPricing: false positive).
- **Incident:** an interrupted `update_page` call left BayesianInference truncated (v7). It was restored the same day from the
  pre-edit snapshot plus the pipeline (v8, verified).
- **Fixer defects found mid-run, then fixed:** a space was inserted before a closing `**` (`**$x$ **`), a superscript was split
  from its word (`DRM $^2$ CS`), and `\a`/`\r` decoded into BEL/CR were not restored.
- **Pending (the permission classifier blocked the corrective push), 23 pages:** AmortizedAnalysis, BiochemicalEngineering, BlackScholesModel, BlockchainMathematics, CSSThemeDark, ClockSynchronization, ColdChainSensorNetworks, CostBenefitAnalysis, CrystallizationTheory, EmbeddingsVectorDB, FactorInvesting, FunctionalAnalysis, FunctionalProgrammingFoundations, GroupTheorySymmetry, InfinityMathematics, InventoryManagementStrategies, PhiAccrualFailureDetector, PhysicsEngineering, PulleySystems, RegressionAnalysis, RelationalDatabaseFundamentals, RemoteGuestEmergencies, TokenBudgeting.
  Four of these are currently WORSE than before round 2 and should go first: AmortizedAnalysis, PhiAccrualFailureDetector and
  PhysicsEngineering (bold broken next to a formula), and RemoteGuestEmergencies (`DRM²CS` split). The rest are unchanged
  from before round 2 or only partly repaired. Each page's final text was generated and checked, and is ready to push.

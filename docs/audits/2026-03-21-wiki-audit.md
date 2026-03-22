# Wiki Audit — 2026-03-21

**Pages:** 186 (pre-audit) → 189 (post-audit) | **Clusters:** 13 → 15 | **Issues Found:** 127 | **Auto-fixed:** 31 | **New Articles:** 3

## Auto-Fix Log

| Page | Action | Detail | Previous |
|------|--------|--------|----------|
| RetirementPlanningGuide | set_metadata | Added type=hub, summary, tags, status, related (10 pages) | cluster only |
| SafeWithdrawalRates | set_metadata | Added type, summary, tags, status, related (8 pages) | cluster only |
| GuardrailsSpendingStrategy | set_metadata | Added full frontmatter block, cluster=retirement-planning | no frontmatter |
| HistoryOfTheFourPercentRule | set_metadata | Added full frontmatter block, cluster=retirement-planning | no frontmatter |
| SequenceOfReturnsRisk | set_metadata | Added date=2026-03-21 | missing date |
| CoastFire | set_metadata | Added full frontmatter block, cluster=retirement-planning | no frontmatter |
| FireMovement | set_metadata | Added full frontmatter block, cluster=retirement-planning | no frontmatter |
| IndexFundInvestingForEarlyRetirement | set_metadata | Added type=hub, summary, tags, status, related (7 pages) | cluster only |
| IndexFundPortfolioConstruction | set_metadata | Added type, summary, tags, status, related | cluster only |
| EarlyRetirementInvestmentPlan | set_metadata | Added type, summary, tags, status, related | cluster only |
| InvestingInYourTwenties | set_metadata | Added date=2026-03-21 | missing date |
| CompoundingIntuition | set_metadata | Added cluster=index-fund-investing | missing cluster |
| AssetAllocationGuide | set_metadata | Added cluster=index-fund-investing | missing cluster |
| LinuxForWindowsUsers | set_metadata | Added type=hub, summary, tags, status, related (7 pages) | no frontmatter |
| WhyLearnLinuxDeeply | set_metadata | Added type, summary, tags, status, cluster, related | no frontmatter |
| AiAugmentedWorkflows | set_metadata | Added type, summary, tags, status, cluster, related | no frontmatter |
| AIModelTraining | set_metadata | Added type=article, status=active, related | missing fields |
| ArtificialIntelligence | set_metadata | Added type=reference, status=active | missing fields |
| MachineLearning | set_metadata | Added type=reference, status=active | missing fields |
| FoundationalAlgorithmsForComputerScientists | set_metadata | Added full frontmatter block, cluster=technology | no frontmatter |
| OperationsResearch | set_metadata | Added full frontmatter block, cluster=operations-research | no frontmatter |
| StochasticModelsInOR | set_metadata | Added full frontmatter, cluster=operations-research | no frontmatter |
| Berlin | set_metadata | Changed to type=hub, cluster=berlin-history, added related | wrong cluster |
| BerlinsTransformationFromMargraviateToCapitalCity | set_metadata | cluster=berlin-history, added type, status, related | wrong cluster |
| ReformationAndUrbanDevelopmentInBerlin | set_metadata | cluster=berlin-history, added type, status, related | wrong cluster |
| ReformationEraInBerlin | set_metadata | Added full frontmatter, cluster=berlin-history | no frontmatter |
| 2026IranWar | set_metadata | Added summary, cluster=conflicts-equity-markets, related | missing fields |
| About | set_metadata | Added related=[Main, WikantikOnDocker, GoodMcpDesign] | missing related |
| WikantikOnDocker | set_metadata | Added cluster=wikantik-operations, related | missing cluster |
| Main | add_clusters | Added Berlin History and Warehouse Automation to hub listing | missing from Main |
| FundamentalsOfProgramming | verified | Already complete — no changes needed | — |

## Cross-Cluster Linking Fixes

| Source Page | Action | Target Page(s) |
|-------------|--------|----------------|
| ConflictsAndEquityMarkets | add_see_also + related | RussiaUkraineWarOverview, RussiaUkraineWarMarkets |
| OperationsResearchHub | add_see_also + related | WarehouseAutomationHub |
| GenerativeAiAdoptionGuide | add_related_link + related | WarehouseAiAndMl |
| RussiaUkraineWarOverview | add_see_also + related | ConflictsAndEquityMarkets, RussiaUkraineWarMarkets |
| EuRetirementSavingsGuide | add_further_reading + related | IndexFundInvestingForEarlyRetirement |
| IndexFundInvestingForEarlyRetirement | add_related + related | RetirementPlanningGuide |
| RetirementPlanningGuide | add_related (frontmatter) | IndexFundInvestingForEarlyRetirement |

## New Articles Created

| Page | Cluster | Bridges | Summary |
|------|---------|---------|---------|
| ConflictResilientPortfolios | index-fund-investing | conflicts-equity-markets ↔ index-fund-investing | Historical conflict patterns informing portfolio construction and retirement withdrawal strategies |
| OptimizationInWarehouseAutomation | warehouse-automation | operations-research ↔ warehouse-automation | How LP, scheduling algorithms, and stochastic models drive automated warehouse efficiency |
| AiDrivenRetirementPlanning | retirement-planning | generative-ai ↔ retirement-planning | How generative AI tools enhance Monte Carlo analysis, tax optimization, and scenario modeling |

## New Clusters Created

| Cluster | Hub Page | Member Pages |
|---------|----------|-------------|
| berlin-history | Berlin | BerlinsTransformationFromMargraviateToCapitalCity, ReformationAndUrbanDevelopmentInBerlin, ReformationEraInBerlin |
| wikantik-operations | — | WikantikOnDocker, GoodMcpDesign |

## Cluster: retirement-planning

**Pages:** 18 (was 16) | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- RetirementPlanningGuide: hub metadata now complete (type, summary, tags, status, related)
- SafeWithdrawalRates: full metadata added
- GuardrailsSpendingStrategy: added to cluster with full metadata
- HistoryOfTheFourPercentRule: added to cluster with full metadata
- CoastFire: added to cluster with full metadata
- FireMovement: added to cluster with full metadata
- AiDrivenRetirementPlanning: new bridge article added
- Cross-linked to index-fund-investing and generative-ai clusters

## Cluster: index-fund-investing

**Pages:** 17 (was 15) | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- IndexFundInvestingForEarlyRetirement: hub metadata now complete
- IndexFundPortfolioConstruction: full metadata added
- EarlyRetirementInvestmentPlan: full metadata added
- CompoundingIntuition: added cluster assignment
- AssetAllocationGuide: added cluster assignment
- ConflictResilientPortfolios: new bridge article added
- Cross-linked to retirement-planning and conflicts-equity-markets

## Cluster: conflicts-equity-markets

**Pages:** 12 (was 11) | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- 2026IranWar: added to cluster with summary and related links
- ConflictsAndEquityMarkets: added cross-references to russia-ukraine-war cluster
- ConflictResilientPortfolios linked from this cluster

## Cluster: russia-ukraine-war

**Pages:** 6 | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- RussiaUkraineWarOverview: added cross-references to conflicts-equity-markets

## Cluster: generative-ai

**Pages:** 11 | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- AiAugmentedWorkflows: full metadata added
- GenerativeAiAdoptionGuide: cross-referenced to warehouse-automation
- AiDrivenRetirementPlanning linked from this cluster

## Cluster: linux-for-windows-users

**Pages:** 10 | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- LinuxForWindowsUsers: hub metadata now complete
- WhyLearnLinuxDeeply: full metadata added

## Cluster: operations-research

**Pages:** 9 (was 7) | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- OperationsResearch: added to cluster with full metadata
- StochasticModelsInOR: added to cluster with full metadata
- OperationsResearchHub: cross-referenced to warehouse-automation
- OptimizationInWarehouseAutomation linked from this cluster

## Cluster: warehouse-automation

**Pages:** 8 (was 7) | Critical: 0 | Warning: 0 | Suggestion: 0

### Fixes Applied
- OptimizationInWarehouseAutomation: new bridge article added
- WarehouseAutomationHub: already had cross-reference to OperationsResearchHub

## Cluster: hobby-woodworking

**Pages:** 7 | Critical: 0 | Warning: 0 | Suggestion: 0

No issues found. All pages have complete metadata.

## Cluster: spousal-green-card

**Pages:** 8 | Critical: 0 | Warning: 0 | Suggestion: 0

No issues found. All pages have complete metadata.

## Cluster: technology

**Pages:** 6 (was 5) | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- FoundationalAlgorithmsForComputerScientists: added to cluster with full metadata

## Cluster: retirement-planning/eu-retirement

**Pages:** 3 | Critical: 0 | Warning: 0 (fixed) | Suggestion: 0

### Fixes Applied
- EuRetirementSavingsGuide: cross-referenced to index-fund-investing

## Cluster: berlin-history (NEW)

**Pages:** 4 | Critical: 0 | Warning: 0 | Suggestion: 0

New cluster created from previously unclustered Berlin history articles. Hub page: Berlin. Added to Main page.

## Wiki-Wide

### Orphaned Pages (remaining after fixes)
Non-system content pages still without incoming links (27):
- ApprovalRequiredForPageChanges, ApprovalRequiredForUserProfiles (system/config)
- CSSBackgroundPatterns, CSSInstagramFilters, CSSPrettifyThemePrism, CSSPrettifyThemeTomorrowNightBlue, CSSRibbon, CSSStripedText, CSSThemeCleanBlue, CSSThemeDark (CSS snippet pages)
- DockerDeployment, JspwikiDeployment, PostgreSQLLocalDeployment, LoggingConfig (deployment docs)
- FullOAuth, OAuthImplementation, RelationalUserDatabase (dev docs)
- MvnCheatSheet, PerformanceEvaluation, RefactorToPatterns (dev reference)
- DevelopingWithPostgresql, ObservabilityDesign (dev docs)
- OneMinuteWiki, News (standalone)
- RandomPage1774007715905, RandomPage1774007748919 (test/random)
- PlainPage (test)

Most remaining orphans are CSS snippets, deployment docs, test pages, or dev reference — not topical content articles.

### Cross-Cluster Gaps (remaining after fixes)
Most critical gaps have been addressed:
- ~~conflicts-equity-markets ↔ russia-ukraine-war~~ FIXED
- ~~operations-research ↔ warehouse-automation~~ FIXED (+ bridge article)
- ~~generative-ai ↔ warehouse-automation~~ FIXED
- ~~conflicts-equity-markets ↔ index-fund-investing~~ FIXED (bridge article)
- ~~retirement-planning ↔ generative-ai~~ FIXED (bridge article)
- ~~index-fund-investing ↔ eu-retirement~~ FIXED

Remaining minor gaps (low priority — loose tag overlap, not strong topical connection):
- hobby-woodworking ↔ technology (shared "tools" tag — different meanings)
- conflicts-equity-markets ↔ operations-research (shared "history" tag — different contexts)

### Duplicate Summaries
No duplicate summaries found.

### Main Page Gaps
All cluster hubs are now listed on Main:
- ~~Warehouse Automation Hub~~ ADDED
- ~~Berlin~~ ADDED

### Broken Links (pre-existing, not introduced by this audit)
Real broken links worth fixing (not template/example placeholders):
- DevelopingWithPostgresql → Overview, Prerequisites, Troubleshooting (3 broken)
- ObservabilityDesign → Install, Service, Unit (3 broken)
- NewUI → Avatar, Search, Theme, ToC (4 broken)
- TestGoogleDoc → TestWiki (1 broken)

These are in development/test pages, not published content articles. Low priority.

## Recommended Tasks

### Priority: Medium
1. **Fix broken links in DevelopingWithPostgresql** — Either create the missing section pages (Overview, Prerequisites, Troubleshooting) or convert the links to anchors/sections within the page
2. **Add .properties files** — 124 pages lack .properties files. While frontmatter handles metadata, .properties files track authorship and markup syntax. Consider a batch script to generate them.

### Priority: Low
3. **Link deployment docs from a hub** — DockerDeployment, JspwikiDeployment, PostgreSQLLocalDeployment, WikantikOnDocker could form a "Deployment & Operations" cluster
4. **Clean up CSS snippet pages** — The 10 CSS pages are orphaned and unclustered. Consider grouping them or archiving
5. **Review Random/Test pages** — RandomPage1774007715905, RandomPage1774007748919, PlainPage, TestGoogleDoc, TestMarkdown, TestPrecedence could be deleted if no longer needed
6. **Add News page to Main** — The News page exists but is orphaned; consider linking it from Main or the navigation

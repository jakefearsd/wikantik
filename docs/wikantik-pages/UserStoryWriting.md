---
cluster: software-engineering-practices
canonical_id: 01KQ0P44Y7MWRMBZR2F1RWN6ZV
title: "User Story Writing & Specification"
type: article
tags: [agile, requirements, gherkin, bdd, testing]
date: 2025-05-15
summary: A guide to writing rigorous User Stories and Acceptance Criteria using Gherkin syntax, focusing on specification-by-example and automated test mapping.
auto-generated: false
---

# User Story Writing: The Rigor of Specification

A User Story is a placeholder for a conversation; its Acceptance Criteria (AC) are the **contract**. For practitioners, writing ACs is not a documentation task—it is an act of systems engineering that bridges intent and execution.

---

## I. The Anatomy of a Story

A well-formed User Story follows the standard template but adds a clear **Validation Boundary**.

*   **As a:** [Persona]
*   **I want to:** [Action]
*   **So that:** [Value]

### The INVEST Principle
*   **Independent:** Can be shipped without other stories.
*   **Negotiable:** Not a rigid contract until the sprint starts.
*   **Valuable:** Delivers clear benefit.
*   **Estimable:** Team understands the scope.
*   **Small:** Fits within a single sprint.
*   **Testable:** Has clear, binary pass/fail criteria.

---

## II. Gherkin Syntax: Given-When-Then

To remove ambiguity, practitioners use **Gherkin**, a domain-specific language for behavior-driven development (BDD).

### Scenario: High-Value Transaction Verification
```gherkin
Feature: Secure Payment Processing

  Scenario: Prevent unauthorized high-value transfers
    Given a user "Alice" with a balance of $10,000
    And the transaction limit for "Alice" is set to $5,000
    When "Alice" attempts to transfer $7,500 to "Bob"
    Then the transaction should be "REJECTED"
    And "Alice" should receive an alert: "Transaction exceeds your limit."
    And the system should log a "Security_Warning" for the audit team.
```

### Advanced AC: Boundary Value Analysis
For a "Discount Code" feature:
*   **AC1:** GIVEN a cart total of $100.00, WHEN code "SAVE10" is applied, THEN total is $90.00.
*   **AC2:** GIVEN a cart total of $0.00, WHEN code "SAVE10" is applied, THEN the system returns an error: "Invalid Cart Total."
*   **AC3:** GIVEN a code that expired at 23:59:59 yesterday, WHEN applied today at 00:00:01, THEN the system returns error: "Code Expired."

---

## III. Automated Acceptance Test Mapping

The power of Gherkin lies in its direct mapping to executable code. This is **Specification by Example**.

### Step Definition Example (Java/Cucumber)
```java
public class PaymentStepDefinitions {

    @Given("a user {string} with a balance of ${int}")
    public void setupUserBalance(String user, int balance) {
        accountService.setBalance(user, balance);
    }

    @When("{string} attempts to transfer ${int} to {string}")
    public void attemptTransfer(String sender, int amount, String recipient) {
        this.response = transactionEngine.process(sender, recipient, amount);
    }

    @Then("the transaction should be {string}")
    public void verifyStatus(String expectedStatus) {
        assertEquals(expectedStatus, response.getStatus());
    }
}
```

---

## IV. The Practitioner's Story Template (YAML)

Use this format to capture stories in a machine-readable, version-controlled repository (e.g., `stories/AUTH-101.yaml`).

```yaml
id: "PROJ-101"
title: "Multi-Factor Authentication (MFA) Setup"
persona: "Security-conscious User"
action: "enable TOTP-based MFA in my profile settings"
value: "protect my account from unauthorized access even if my password is leaked"

acceptance_criteria:
  - id: "AC1"
    scenario: "Successful MFA setup"
    given: "User is logged in and on the Security settings page"
    when: "User scans the QR code and enters the correct 6-digit TOTP token"
    then: "System marks 'mfa_enabled: true' in the DB and displays success message"

  - id: "AC2"
    scenario: "Invalid token rejection"
    given: "User is on the MFA setup screen"
    when: "User enters an incorrect 6-digit token"
    then: "System returns 'Invalid Code' and does not enable MFA"

non_functional_requirements:
  performance: "TOTP validation must complete in < 100ms"
  security: "TOTP secret must be stored using AES-256 encryption at rest"
```

---

## V. Critical Failure Modes

1.  **The "Checklist" AC:** Listing tasks ("Update the DB table") instead of behaviors ("System persists the record").
2.  **Missing "Given":** Assuming a system state that isn't explicitly defined, leading to flaky automated tests.
3.  **Ambiguous "Then":** Using subjective words like "User sees a beautiful UI" instead of "The `success-toast` element is visible."

**Rule of Thumb:** If a developer cannot write a test case from your AC without asking a follow-up question, the AC is incomplete.

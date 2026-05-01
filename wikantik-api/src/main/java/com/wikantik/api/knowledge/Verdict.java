package com.wikantik.api.knowledge;

/**
 * Output of a {@link ProposalJudge}. Sealed: every judge returns one of
 * three verdicts. {@code Reject.reasonCode} is a small closed enum
 * (see ProposalJudge contract test) used for metrics keying.
 */
public sealed interface Verdict {

    record Accept(double finalConfidence, String rationale) implements Verdict {}

    record Reject(String reasonCode, String rationale) implements Verdict {
        public Reject {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }

    record Rewrite(ConsolidatedProposal rewritten, String rationale) implements Verdict {
        public Rewrite {
            if (rewritten == null) {
                throw new IllegalArgumentException("rewritten must not be null");
            }
        }
    }
}

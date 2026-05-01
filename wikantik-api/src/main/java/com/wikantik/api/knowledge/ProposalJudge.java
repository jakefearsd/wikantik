package com.wikantik.api.knowledge;

/**
 * Optional verdict step that runs on consolidated proposals. Default
 * implementation in production is {@code NoOpProposalJudge} (accepts all);
 * Ollama and Claude judges are opt-in.
 *
 * <p>Closed enum of {@code Reject.reasonCode} values: ungrounded,
 * redundant_with_existing_node, wrong_type, too_generic, weak_support.
 * Implementations that need to fail-open due to internal error should
 * return {@code Accept(p.aggregateConfidence(), "judge_failed: ...")}.
 */
public interface ProposalJudge {
    String code();

    Verdict judge(ConsolidatedProposal proposal, JudgeContext context);
}

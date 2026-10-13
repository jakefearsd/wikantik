-- kg-reset-machine-tier.sql -- the destructive core of `bin/kg-rebuild.sh --reset-kg`.
--
-- Prunes MACHINE-TIER Knowledge Graph state so extraction can start from a clean slate, and
-- touches nothing a person has written or approved. Run in ONE transaction by kg-rebuild.sh
-- (psql --single-transaction -v ON_ERROR_STOP=1 -f), so the two statements apply together or
-- not at all. Regression-pinned by bin/tests/test-kg-rebuild-reset.sh and by
-- KgResetMachineTierSqlTest (real PostgreSQL).
--
-- WHY THE TIER GUARD IS REQUIRED
-- ------------------------------
-- kg_nodes.provenance and kg_nodes.tier are independent columns (V024):
--   provenance  where the row came from        human-authored | human-curated | ai-inferred | ai-reviewed
--   tier        who vouches for it now         machine | human            (CHECK-constrained, no other value)
-- Approving a proposal changes the TIER to 'human' (KgMaterializationService.promoteToHuman ->
-- updateTierByProvenance) while the PROVENANCE stays 'ai-inferred' -- the row records that a
-- person approved what an AI proposed. Selecting on provenance alone therefore also deleted
-- every human-approved node, and (via ON DELETE CASCADE) the edges, chunk mentions and node
-- embeddings hanging off it.
--
-- WHAT IS DELETED
--   1. kg_proposals WHERE status = 'pending'. kg_proposals_human_terminal_check
--      (tier <> 'human' OR status IN ('approved','rejected')) makes a pending proposal
--      machine/none-tier by construction, so no human verdict can be lost here.
--      Cascades to kg_proposal_reviews (the machine verdicts on those proposals).
--   2. kg_nodes that are provenance 'ai-inferred' AND tier 'machine'. Cascades to the
--      kg_edges incident on them, chunk_entity_mentions and kg_node_embeddings (all FK
--      ON DELETE CASCADE).
--
-- WHAT IS KEPT
--   * every tier = 'human' node (human-authored, human-curated, human-approved), and every
--     tier = 'human' edge;
--   * a machine-tier ai-inferred node that is an endpoint of a tier = 'human' edge. Deleting it
--     would cascade-delete that human-approved edge, so the node is kept -- the reset deletes
--     only machine-tier rows, edges included;
--   * all nodes whose provenance is not 'ai-inferred'.

DELETE FROM kg_proposals
 WHERE status = 'pending';

DELETE FROM kg_nodes n
 WHERE n.provenance = 'ai-inferred'
   AND n.tier = 'machine'
   AND NOT EXISTS ( SELECT 1
                      FROM kg_edges e
                     WHERE e.tier = 'human'
                       AND ( e.source_id = n.id OR e.target_id = n.id ) );

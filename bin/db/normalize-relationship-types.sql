-- Operator script: one-shot normalization of kg_edges.relationship_type and
-- kg_proposals.proposed_data->>'relationship' to the closed 20-type vocabulary
-- enforced by V027__kg_relationship_type_check.sql.
--
-- Per `feedback_no_data_backfill_in_migrations`: this is a data fixup, NOT a
-- versioned migration. Run once against an existing database, then apply V027.
--
-- Usage:
--   PGPASSWORD=... psql -h localhost -U jspwiki -d jspwiki \
--       -v ON_ERROR_STOP=1 -f bin/db/normalize-relationship-types.sql
--
-- The whole script runs in a single transaction. Inspect the diagnostic SELECTs
-- printed at the end before deciding to commit (the script ends with COMMIT;
-- swap to ROLLBACK; for a dry-run).

BEGIN;

-- --------------------------------------------------------------------------
-- Closed vocabulary (20 types). Keep this list in sync with V027's CHECK
-- constraint and the entity-extractor prompt.
-- --------------------------------------------------------------------------
--   related_to       part_of         contains          is_a
--   instance_of      requires        enables           uses
--   produces         replaces        precedes          extends
--   implements       alternative_to  contrasts_with    compatible_with
--   mitigates        defines         applies_to        located_in
-- --------------------------------------------------------------------------

CREATE TEMP TABLE _rt_map (
    norm_in   TEXT PRIMARY KEY,
    canonical TEXT NOT NULL
);

-- Mapping key is LOWER(REGEXP_REPLACE(input, '[\s\-_]+', '_', 'g')) so
-- separator/case variants collapse to one row. e.g. "Related To",
-- "related_to", "is-related-to" all key on "related_to" / "is_related_to".

INSERT INTO _rt_map(norm_in, canonical) VALUES
    -- related_to (generic association catch-all)
    ('related',                          'related_to'),
    ('related_to',                       'related_to'),
    ('is_related_to',                    'related_to'),
    ('relates_to',                       'related_to'),
    ('associated_with',                  'related_to'),
    ('is_associated_with',               'related_to'),
    ('is_compared_to',                   'related_to'),
    ('is_compared_with',                 'related_to'),
    ('compared_to',                      'related_to'),
    ('compares',                         'related_to'),
    ('is_connected_by_road_segment',     'related_to'),
    ('combined_with',                    'related_to'),
    ('combines',                         'related_to'),
    ('combines_ideas_with',              'related_to'),
    ('combines_aspects_of',              'related_to'),
    ('connection',                       'related_to'),
    ('communicates_with',                'related_to'),
    ('interact_with',                    'related_to'),
    ('system_interaction',               'related_to'),
    ('coexists',                         'related_to'),
    ('coexistence',                      'related_to'),
    ('compatibility',                    'related_to'),
    ('integration',                      'related_to'),
    ('integrates',                       'related_to'),
    ('integrates_with',                  'related_to'),

    -- part_of (X is part of Y)
    ('part_of',                          'part_of'),
    ('is_part_of',                       'part_of'),
    ('is_a_part_of',                     'part_of'),
    ('is_component_of',                  'part_of'),
    ('is_a_component_of',                'part_of'),
    ('was_part_of',                      'part_of'),
    ('is_a_subset_of',                   'part_of'),
    ('subset',                           'part_of'),
    ('belongs_to',                       'part_of'),
    ('is_in',                            'part_of'),

    -- contains (X contains Y — directional emphasis on container)
    ('contains',                         'contains'),
    ('includes',                         'contains'),
    ('includes_element',                 'contains'),
    ('includes_step',                    'contains'),
    ('includes_examples_like',           'contains'),
    ('includes_methodologies_of',        'contains'),
    ('includes_perspective',             'contains'),
    ('includes_application_for',         'contains'),
    ('has_component',                    'contains'),
    ('has_feature',                      'contains'),
    ('contains_endpoint_for',            'contains'),
    ('contains_a_subset_called',         'contains'),
    ('component',                        'contains'),
    ('covers',                           'contains'),

    -- is_a (subtype/class membership)
    ('is_a',                             'is_a'),
    ('is_a_type_of',                     'is_a'),
    ('is_a_form_of',                     'is_a'),
    ('is_a_variant_of',                  'is_a'),
    ('is_a_kind_of',                     'is_a'),
    ('is_a_subtype_of',                  'is_a'),
    ('variant',                          'is_a'),
    ('is_also_known_as',                 'is_a'),

    -- instance_of (concrete example of)
    ('is_an_example_of',                 'instance_of'),
    ('is_a_canonical_example_of',        'instance_of'),
    ('example',                          'instance_of'),
    ('are_examples_of',                  'instance_of'),

    -- requires (hard dependency)
    ('requires',                         'requires'),
    ('depends_on',                       'requires'),
    ('depends_upon',                     'requires'),
    ('must_have',                        'requires'),
    ('must_incorporate',                 'requires'),
    ('must_account_for',                 'requires'),
    ('must_validate',                    'requires'),
    ('must_file',                        'requires'),
    ('requires_separation_from',         'requires'),
    ('requires_verification_using',      'requires'),
    ('requires_calculation_of',          'requires'),
    ('requires_evaluation_by',           'requires'),
    ('requires_enrollment_in',           'requires'),
    ('requires_structured_output',       'requires'),
    ('requires_form',                    'requires'),

    -- enables (capability/affordance)
    ('enables',                          'enables'),
    ('allows',                           'enables'),
    ('allows_owner_to_participate_as',   'enables'),
    ('allows_penalty_free_withdrawal_from','enables'),
    ('allows_enabling_strict_checks_in', 'enables'),
    ('allows_borrowing_more_than_owed_to_fund','enables'),
    ('enable_abstractions_like',         'enables'),
    ('provides',                         'enables'),
    ('provides_a_framework_for',         'enables'),
    ('provides_strategies_for',          'enables'),
    ('provides_structure_for',           'enables'),
    ('provides_foundation_for',          'enables'),
    ('provides_environment_for',         'enables'),
    ('provides_environment',             'enables'),
    ('provides_canonical_source_for_patterns','enables'),
    ('provides_information_on',          'enables'),
    ('provides_benefit_for',             'enables'),
    ('provides_local_state_management',  'enables'),
    ('provides_a_placeholder_for',       'enables'),
    ('provides_stable_network_identity_for','enables'),
    ('grants_based_on',                  'enables'),
    ('supports',                         'enables'),
    ('facilitates',                      'enables'),

    -- uses (directional usage; A uses B)
    ('uses',                             'uses'),
    ('utilizes',                         'uses'),
    ('utilizes_structure_from',          'uses'),
    ('uses_strategy',                    'uses'),
    ('uses_query_language',              'uses'),
    ('uses_language',                    'uses'),
    ('uses_cipher',                      'uses'),
    ('uses_fetch_type',                  'uses'),
    ('uses_for_streaming_responses',     'uses'),
    ('uses_for_spending',                'uses'),
    ('uses_hook_for_consumption',        'uses'),
    ('uses_standard',                    'uses'),
    ('uses_selector_to_discover_endpoints_from','uses'),
    ('uses_as_schema_and_serialisation_language','uses'),
    ('uses_general_purpose_language',    'uses'),
    ('calls',                            'uses'),
    ('invokes',                          'uses'),
    ('reads_from',                       'uses'),
    ('consumes_and_generates_from',      'uses'),
    ('processes_data_sources_like',      'uses'),
    ('retrievesfrom',                    'uses'),
    ('saves_to',                         'uses'),
    ('stores_searchable_text_as',        'uses'),
    ('operates_on',                      'uses'),
    ('applies_strategy',                 'uses'),
    ('runs_on',                          'uses'),

    -- produces (output relation)
    ('produces',                         'produces'),
    ('generates',                        'produces'),
    ('generates_facade_for',             'produces'),
    ('publishes',                        'produces'),
    ('emits',                            'produces'),
    ('computes',                         'produces'),

    -- replaces (supersession)
    ('replaces',                         'replaces'),
    ('supersedes',                       'replaces'),
    ('replacement',                      'replaces'),
    ('replaces_transport_for',           'replaces'),
    ('modernization_of',                 'replaces'),

    -- precedes (temporal ordering)
    ('precedes',                         'precedes'),
    ('is_precursor_to',                  'precedes'),
    ('preceded',                         'precedes'),
    ('comes_before',                     'precedes'),

    -- extends (specialization / builds on)
    ('extends',                          'extends'),
    ('extension_of',                     'extends'),
    ('is_an_extension_of',               'extends'),
    ('is_an_evolution_of',               'extends'),
    ('builds_on',                        'extends'),
    ('builds_upon',                      'extends'),
    ('builds_upon_concept_of',           'extends'),
    ('is_built_on',                      'extends'),
    ('evolution',                        'extends'),
    ('sits_atop',                        'extends'),
    ('expands',                          'extends'),
    ('extension_on_top_of',              'extends'),
    ('enhances',                         'extends'),
    ('improved_over',                    'extends'),
    ('improved_functionality_with',      'extends'),
    ('improves_performance_of',          'extends'),
    ('adds_static_types_to',             'extends'),

    -- implements (concrete realization)
    ('implements',                       'implements'),
    ('is_client_side_implementation_of', 'implements'),
    ('realizes',                         'implements'),

    -- alternative_to (substitute)
    ('alternative',                      'alternative_to'),
    ('alternativeto',                    'alternative_to'),
    ('is_alternative_to',                'alternative_to'),
    ('is_an_alternative_to',             'alternative_to'),
    ('alternative_source_for',           'alternative_to'),
    ('is_a_substitute_for',              'alternative_to'),
    ('substitute_for',                   'alternative_to'),

    -- contrasts_with (differential)
    ('contrasts_with',                   'contrasts_with'),
    ('is_contrasted_with',               'contrasts_with'),
    ('differs_from',                     'contrasts_with'),
    ('different_from',                   'contrasts_with'),
    ('is_distinct_from',                 'contrasts_with'),
    ('distinct_from',                    'contrasts_with'),
    ('comparison',                       'contrasts_with'),
    ('is_not_topologically_equivalent_to','contrasts_with'),

    -- compatible_with (interop)
    ('compatible_with',                  'compatible_with'),
    ('compatiblewith',                   'compatible_with'),
    ('is_compatible_with',               'compatible_with'),
    ('is_compliant_with',                'compatible_with'),
    ('can_be_paired_with',               'compatible_with'),
    ('complements',                      'compatible_with'),
    ('can_be_filed_concurrently_with',   'compatible_with'),

    -- mitigates (risk/harm reduction)
    ('mitigates',                        'mitigates'),
    ('prevents',                         'mitigates'),
    ('minimizes',                        'mitigates'),
    ('counters',                         'mitigates'),
    ('antidote',                         'mitigates'),
    ('addresses',                        'mitigates'),
    ('is_addressed_by',                  'mitigates'),
    ('can_address',                      'mitigates'),

    -- defines (provides definition/specification)
    ('defines',                          'defines'),
    ('defined',                          'defines'),
    ('determines',                       'defines'),
    ('defines_outcomes_of',              'defines'),
    ('defined_outcomes_of',              'defines'),
    ('defined_the_relation',             'defines'),
    ('defines_the_role_of',              'defines'),
    ('defines_the_limit_of',             'defines'),
    ('defines_trade_off_between',        'defines'),
    ('describes',                        'defines'),
    ('described',                        'defines'),
    ('documents',                        'defines'),
    ('introduces_concept',               'defines'),
    ('introduces',                       'defines'),
    ('introduced',                       'defines'),
    ('formalized',                       'defines'),
    ('is_a_standard_provided_by',        'defines'),
    ('is_defined_as',                    'defines'),
    ('standardized_metric',              'defines'),
    ('governs',                          'defines'),

    -- applies_to (scope of relevance)
    ('appliesto',                        'applies_to'),
    ('applies_to',                       'applies_to'),
    ('is_relevant_to',                   'applies_to'),
    ('is_concerned_with_coordinating_behavior_within_the_boundaries_of','applies_to'),
    ('applies_to_domain',                'applies_to'),

    -- located_in (spatial / containment-in-place)
    ('locatedin',                        'located_in'),
    ('location',                         'located_in'),
    ('is_located_in',                    'located_in'),
    ('located_in',                       'located_in'),
    ('established_location',             'located_in'),
    ('is_where_complex_numbers_are_plotted','located_in')
;

-- --------------------------------------------------------------------------
-- kg_edges normalization
-- --------------------------------------------------------------------------
-- 1. Identify the survivor row for each (source_id, target_id, canonical)
--    tuple, preferring human-authored & human-tier & oldest.
-- 2. DELETE all rows that either map to NULL (no canonical) or are not the
--    chosen survivor of their (src,tgt,canonical) group.
-- 3. UPDATE the surviving rows to their canonical relationship_type.

CREATE TEMP TABLE _edge_survivors AS
WITH ranked AS (
    SELECT
        e.id,
        m.canonical AS canon_rt,
        ROW_NUMBER() OVER (
            PARTITION BY e.source_id, e.target_id, m.canonical
            ORDER BY
                CASE WHEN e.provenance = 'human-authored' THEN 0 ELSE 1 END,
                CASE WHEN e.tier = 'human' THEN 0 ELSE 1 END,
                e.created ASC
        ) AS rn
    FROM kg_edges e
    JOIN _rt_map m ON LOWER(REGEXP_REPLACE(e.relationship_type, '[\s\-_]+', '_', 'g')) = m.norm_in
)
SELECT id, canon_rt FROM ranked WHERE rn = 1;

CREATE INDEX ON _edge_survivors(id);

-- before / after diagnostics
SELECT 'edges before:'                AS phase, COUNT(*) AS n FROM kg_edges
UNION ALL
SELECT 'edges with mappable type:',         COUNT(*) FROM kg_edges e
    JOIN _rt_map m ON LOWER(REGEXP_REPLACE(e.relationship_type, '[\s\-_]+', '_', 'g')) = m.norm_in
UNION ALL
SELECT 'edges in survivor set:',            COUNT(*) FROM _edge_survivors;

DELETE FROM kg_edges
 WHERE id NOT IN (SELECT id FROM _edge_survivors);

UPDATE kg_edges e
   SET relationship_type = s.canon_rt
  FROM _edge_survivors s
 WHERE e.id = s.id
   AND e.relationship_type <> s.canon_rt;

-- --------------------------------------------------------------------------
-- kg_proposals normalization (pending new-edge proposals only)
-- --------------------------------------------------------------------------
-- Proposed edges have proposed_data->>'relationship' carrying the predicate.
-- Strategy: rewrite the JSONB to the canonical form where mappable, DELETE
-- the rest. Rejected/approved proposals are historical record — left alone.

UPDATE kg_proposals p
   SET proposed_data = p.proposed_data || jsonb_build_object('relationship', m.canonical)
  FROM _rt_map m
 WHERE p.status = 'pending'
   AND p.proposal_type = 'new-edge'
   AND p.proposed_data ? 'relationship'
   AND LOWER(REGEXP_REPLACE(p.proposed_data->>'relationship', '[\s\-_]+', '_', 'g')) = m.norm_in
   AND p.proposed_data->>'relationship' <> m.canonical;

DELETE FROM kg_proposals p
 WHERE p.status = 'pending'
   AND p.proposal_type = 'new-edge'
   AND (
        NOT (p.proposed_data ? 'relationship')
        OR LOWER(REGEXP_REPLACE(p.proposed_data->>'relationship', '[\s\-_]+', '_', 'g'))
           NOT IN (SELECT norm_in FROM _rt_map)
   );

-- --------------------------------------------------------------------------
-- Post-state diagnostics
-- --------------------------------------------------------------------------
SELECT 'AFTER edges total:'           AS phase, COUNT(*) AS n FROM kg_edges
UNION ALL
SELECT 'AFTER distinct rel types:',         COUNT(DISTINCT relationship_type) FROM kg_edges
UNION ALL
SELECT 'AFTER pending new-edge proposals:', COUNT(*) FROM kg_proposals WHERE status='pending' AND proposal_type='new-edge';

SELECT relationship_type, COUNT(*) AS n
  FROM kg_edges
 GROUP BY relationship_type
 ORDER BY n DESC;

-- Sanity-check: every surviving relationship_type is in the closed vocabulary.
-- If this returns any rows, something slipped through — DO NOT commit.
SELECT 'LEAKED — not in closed vocab:' AS msg, relationship_type, COUNT(*) AS n
  FROM kg_edges
 WHERE relationship_type NOT IN (
    'related_to','part_of','contains','is_a','instance_of',
    'requires','enables','uses','produces','replaces',
    'precedes','extends','implements','alternative_to','contrasts_with',
    'compatible_with','mitigates','defines','applies_to','located_in'
 )
 GROUP BY relationship_type;

COMMIT;

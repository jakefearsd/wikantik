-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- V017: Seed the `core-agent-queries` retrieval-quality query set with one
-- natural-language question per agent-cookbook runbook plus one cross-cluster
-- query. Each row's `expected_ids` column lists the canonical_ids that an
-- effective retriever ought to surface in the top-K.
--
-- See docs/superpowers/plans/2026-04-25-agent-grade-content-phase-5.md for
-- the curation method. Idempotent (`ON CONFLICT DO NOTHING`).

INSERT INTO retrieval_query_sets (id, name, description) VALUES
    ('core-agent-queries',
     'Core agent queries',
     'Hand-curated questions an agent typically asks; expected_ids point at the agent-cookbook runbook(s) that answer them. One query per cookbook page plus one cross-cluster query (HybridRetrieval + HandlingEmbeddingServiceOutages).')
ON CONFLICT (id) DO NOTHING;

INSERT INTO retrieval_queries (query_set_id, query_id, query_text, expected_ids) VALUES
    ('core-agent-queries', 'q01', 'how do I cite a wiki page',                          ARRAY['01KQ419DJ32TGYRV7ZDVE83M9V']),
    ('core-agent-queries', 'q02', 'how do I pick a retrieval mode',                     ARRAY['01KQ4MTH3GHP1521AWH0CCQTHD']),
    ('core-agent-queries', 'q03', 'find the right MCP tool for a task',                 ARRAY['01KQ445HP8DER94KF2WJZZ8JZ5']),
    ('core-agent-queries', 'q04', 'write a new MCP tool',                                ARRAY['01KQ4MEJDNZGZERT87862DAKXX']),
    ('core-agent-queries', 'q05', 'verify an AI generated page',                         ARRAY['01KQ4EMR2624F7E7F8PM46AEHG']),
    ('core-agent-queries', 'q06', 'plan a database migration',                           ARRAY['01KQ4D6CH7MGBCGE5QSNEDS00C']),
    ('core-agent-queries', 'q07', 'debug failing integration tests',                     ARRAY['01KQ485C06BMPZ28QRZK8NM8K1']),
    ('core-agent-queries', 'q08', 'embedding service is down',                           ARRAY['01KQ4QYF0V2A08QN5MCR3M4C6P']),
    ('core-agent-queries', 'q09', 'propose new knowledge graph edges',                   ARRAY['01KQ4H83ZYTDAAMXFSCP27V7F6']),
    ('core-agent-queries', 'q10', 'interpret hybrid retrieval prometheus metrics',       ARRAY['01KQ427DYFF50310Q971E5GB5C']),
    ('core-agent-queries', 'q11', 'build and deploy locally',                            ARRAY['01KQ4VGC5PKAFHNV8CBTXRW0HF']),
    ('core-agent-queries', 'q12', 'agent cited a function that does not exist',          ARRAY['01KQ4CB7EGZZKQ1EQMYWFXKSHF']),
    ('core-agent-queries', 'q13', 'explore a module API surface',                        ARRAY['01KQ4ASQXJM6XG48K38W1GSQPR']),
    ('core-agent-queries', 'q14', 'run the retrieval quality harness',                   ARRAY['01KQ42G7FNHJXBFF246W07JZ3K']),
    ('core-agent-queries', 'q15', 'how does hybrid retrieval work and fail',
        ARRAY['01KQ0P44QZYNK9BZ0FV5NQ9CF8','01KQ4QYF0V2A08QN5MCR3M4C6P']),
    ('core-agent-queries', 'q16', 'agent cookbook',                                       ARRAY['01KQ44QTJGS34S8VKGDMPTK9TC'])
ON CONFLICT (query_set_id, query_id) DO NOTHING;

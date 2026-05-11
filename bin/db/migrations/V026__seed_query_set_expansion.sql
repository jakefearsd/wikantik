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

-- V026: Seed `core-agent-queries-expanded` — 20 cross-cluster queries that
-- exercise the rerank on a wider topic surface than `core-agent-queries`
-- (which is heavily wikantik-development).
--
-- Curation method: pages were picked by clustering breadth and topic
-- specificity from `docs/wikantik-pages/` frontmatter. Each query has a
-- single gold `canonical_id`. Subcategory targeting (mixed-tier edges /
-- high-confidence mentions) is best-effort without per-edge DB sampling;
-- diversity across clusters is the primary correctness axis. Cross-cluster
-- query (q20) intentionally bridges two clusters so the graph rerank can
-- be exercised on a non-trivial bridging traversal. Negative-control
-- queries are direct-title BM25-easy queries where graph signal should
-- not be needed (q15, q18). Idempotent (`ON CONFLICT DO NOTHING`).

INSERT INTO retrieval_query_sets (id, name, description) VALUES
    ('core-agent-queries-expanded',
     'Core agent queries (expanded, cross-cluster)',
     '20 hand-curated questions spanning agentic-ai, retirement-planning, devops-sre, machine-learning, generative-ai, databases, security, distributed-systems, berlin-history, data-engineering, plus one cross-cluster bridge. Used together with core-agent-queries to A/B retrieval-mode variants on a wider topic surface.')
ON CONFLICT (id) DO NOTHING;

INSERT INTO retrieval_queries (query_set_id, query_id, query_text, expected_ids) VALUES
    -- agentic-ai (5)
    ('core-agent-queries-expanded', 'q01', 'how do agent loops iterate to make progress',         ARRAY['01KQ12YDR96H86F5DJH6W9035G']),
    ('core-agent-queries-expanded', 'q02', 'techniques for agent memory across long sessions',    ARRAY['01KQ12YDRBZ9RPVCT0GCH9HG6Y']),
    ('core-agent-queries-expanded', 'q03', 'agent observability and tracing',                     ARRAY['01KQ12YDRCXJHHTSSSDRY6JTWR']),
    ('core-agent-queries-expanded', 'q04', 'how should an agent plan a multi step task',          ARRAY['01KQ12YDRDY7T6HDG0N4W0CT2N']),
    ('core-agent-queries-expanded', 'q05', 'reasoning patterns for LLM agents',                   ARRAY['01KQ12YDRF456K824EWF9801KS']),
    -- retirement-planning (3)
    ('core-agent-queries-expanded', 'q06', 'annuities versus systematic withdrawals in retirement', ARRAY['01KQ0P44KRJ3VV5P4Y8TMTDRXE']),
    ('core-agent-queries-expanded', 'q07', 'bond ladder strategy for retirement income',          ARRAY['01KQ0P44MPZAYHM7EVT2MAH2FM']),
    ('core-agent-queries-expanded', 'q08', 'bucket approach to drawing retirement income',        ARRAY['01KQ0P44MQJ5T5JDVVPRFSTA26']),
    -- devops-sre (2)
    ('core-agent-queries-expanded', 'q09', 'canary deployment rollout pattern',                   ARRAY['01KQ0P44MXK2X5DVXZ0MT3YVKD']),
    ('core-agent-queries-expanded', 'q10', 'ci cd pipeline design',                               ARRAY['01KQ0P44N8EYGZDNWNF37FQQSV']),
    -- machine-learning (2)
    ('core-agent-queries-expanded', 'q11', 'how do convolutional neural networks work',           ARRAY['01KQEKGD90957BN0YHMXH9E35Y']),
    ('core-agent-queries-expanded', 'q12', 'anomaly detection techniques in time series',         ARRAY['01KQ0P44KTD1J1NF6BTMNGN383']),
    -- generative-ai (2)
    ('core-agent-queries-expanded', 'q13', 'prompt engineering tactics for agents',               ARRAY['01KQ0P44JXAB43ME5MS8TV0AKK']),
    ('core-agent-queries-expanded', 'q14', 'fine tuning large language models',                   ARRAY['01KQ0P44QDNYW8AESQS6MKEEZJ']),
    -- databases (1) — negative control: direct title match, BM25-easy
    ('core-agent-queries-expanded', 'q15', 'ACID transactions and isolation',                     ARRAY['01KQEB19KH3481M90CM2A06KSY']),
    -- security (1)
    ('core-agent-queries-expanded', 'q16', 'container security hardening',                        ARRAY['01KQEKGD8XE3Z6DEM2XJXNAEYP']),
    -- distributed-systems (1)
    ('core-agent-queries-expanded', 'q17', 'CAP theorem tradeoffs',                               ARRAY['01KQ0P44MXABK0GYBHM7CD57Q6']),
    -- berlin-history (1) — negative control: domain-specific terms, BM25-easy
    ('core-agent-queries-expanded', 'q18', 'Berlin during the cold war',                          ARRAY['01KQ0P44MG9H3JQTZPP7P3GCNF']),
    -- data-engineering (1)
    ('core-agent-queries-expanded', 'q19', 'apache kafka event streaming fundamentals',           ARRAY['01KQ12YDS1BCMTAA32328JPVAD']),
    -- cross-cluster bridge: agentic-ai × retirement-planning
    ('core-agent-queries-expanded', 'q20', 'using AI to drive retirement planning',               ARRAY['01KQ0P44K5QSXFVR8PW9YFYVS1'])
ON CONFLICT (query_set_id, query_id) DO NOTHING;

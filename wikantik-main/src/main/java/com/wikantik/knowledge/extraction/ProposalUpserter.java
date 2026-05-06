/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.knowledge.KgProposalRepository;

/**
 * Wraps the DB-side upsert. Computes the JSON support payload, delegates to
 * the repository's prepared-statement upsert, returns inserted/merged + the
 * resulting support_count for status logging.
 */
public final class ProposalUpserter {

    private final KgProposalRepository proposals;

    public ProposalUpserter( final KgProposalRepository proposals ) {
        this.proposals = proposals;
    }

    public Result upsert( final ConsolidatedProposal cp ) {
        return proposals.upsertConsolidatedProposal( cp );
    }

    public record Result(boolean inserted, int supportCount) {}
}

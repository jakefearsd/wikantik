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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.Verdict;

import java.util.Locale;
import java.util.Set;

/**
 * Parses a judge LLM's JSON verdict into a {@link Verdict}. Shared by the
 * Claude and Ollama {@link ProposalJudge} implementations so the two backends
 * interpret verdicts identically (an apples-to-apples requirement for A/B
 * comparisons). Always fails open — any parse failure or unknown verdict
 * degrades to {@code Accept} with a {@code judge_failed:} rationale rather than
 * dropping the proposal.
 */
final class ProposalVerdictParser {

    private static final Set< String > ALLOWED_REASONS = Set.of(
        "ungrounded", "redundant_with_existing_node", "wrong_type", "too_generic", "weak_support" );

    private ProposalVerdictParser() {}

    static Verdict parse( final String raw, final ConsolidatedProposal proposal ) {
        if( raw == null ) {
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: empty response" );
        }
        try {
            final JsonObject obj = JsonParser.parseString( raw ).getAsJsonObject();
            final String verdict = obj.get( "verdict" ).getAsString().toLowerCase( Locale.ROOT );
            final String reason  = obj.has( "reason_code" )
                ? obj.get( "reason_code" ).getAsString() : "ok";
            final String rationale = obj.has( "rationale" )
                ? obj.get( "rationale" ).getAsString() : "";
            return switch( verdict ) {
                case "accept"  -> new Verdict.Accept( proposal.aggregateConfidence(), rationale );
                case "reject"  -> new Verdict.Reject(
                    ALLOWED_REASONS.contains( reason ) ? reason : "weak_support", rationale );
                // Rewrite path: full canonicalization is deferred — a parsed but
                // unused 'rewritten' object would regress to Accept-with-rationale
                // anyway, so for now we fail open and leave the proposal untouched.
                case "rewrite" -> new Verdict.Accept( proposal.aggregateConfidence(),
                    "judge_failed: rewrite parsing not implemented yet" );
                default -> new Verdict.Accept( proposal.aggregateConfidence(),
                    "judge_failed: unknown verdict " + verdict );
            };
        } catch( final RuntimeException e ) {
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: " + e.getMessage() );
        }
    }
}

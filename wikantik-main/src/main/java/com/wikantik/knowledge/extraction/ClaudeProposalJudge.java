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
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.http.HttpClient;

/**
 * Mirror of {@link OllamaProposalJudge} that talks to the Anthropic Messages
 * API. Reuses {@link OllamaProposalJudge#SYSTEM_PROMPT} so A/B comparisons
 * between the two backends stay apples-to-apples. Missing API keys, HTTP
 * errors, and malformed responses fail open as
 * {@code Accept(judge_failed: ...)}.
 *
 * <p>Production gating lives at the CLI level
 * ({@code -Dwikantik.kg.judge.allow_claude=true}); the class itself is
 * callable from tests without the gate.
 */
public final class ClaudeProposalJudge implements ProposalJudge {

    private static final Logger LOG = LogManager.getLogger( ClaudeProposalJudge.class );

    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final HttpClient httpClient;

    public ClaudeProposalJudge( final String apiKey, final String model, final long timeoutMs ) {
        this( apiKey, model, timeoutMs, HttpClient.newHttpClient() );
    }

    /** Test-visible constructor — inject a mock {@link HttpClient}. */
    ClaudeProposalJudge( final String apiKey, final String model, final long timeoutMs,
                         final HttpClient httpClient ) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.httpClient = httpClient;
    }

    @Override
    public String code() {
        return "claude:" + model;
    }

    @Override
    public Verdict judge( final ConsolidatedProposal proposal, final JudgeContext context ) {
        if( apiKey == null || apiKey.isBlank() ) {
            return new Verdict.Accept( proposal.aggregateConfidence(),
                "judge_failed: ANTHROPIC_API_KEY missing" );
        }
        try {
            final String raw = callAnthropic( proposal, context );
            return ProposalVerdictParser.parse( raw, proposal );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: interrupted" );
        } catch( final IOException | RuntimeException e ) {
            LOG.warn( "ClaudeProposalJudge failed for {}: {}", proposal.signature(), e.getMessage() );
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: " + e.getMessage() );
        }
    }

    private String callAnthropic( final ConsolidatedProposal p, final JudgeContext c )
            throws IOException, InterruptedException {
        final String userPrompt = OllamaProposalJudge.buildUserPrompt( p, c );
        return AnthropicHttpCaller.call( httpClient, apiKey, model, timeoutMs, 1024,
            OllamaProposalJudge.SYSTEM_PROMPT, userPrompt,
            ( statusCode, respBody ) ->
                LOG.warn( "ClaudeProposalJudge HTTP {} for sig {}: {}", statusCode, p.signature(), respBody ),
            message -> LOG.warn( "ClaudeProposalJudge non-JSON body for sig {}: {}", p.signature(), message ) );
    }

}

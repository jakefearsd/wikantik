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
package com.wikantik.llm.activity;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;

/**
 * {@link KgProposalJudgeService} decorator that records each judge call into an
 * {@link LlmActivityLog}. The underlying judge throws on backend failure, so failed
 * calls are recorded as ERROR; transient-unavailable verdicts return normally and
 * are recorded OK (the rationale text surfaces in the response preview).
 */
public final class RecordingKgProposalJudgeService implements KgProposalJudgeService {

    private final KgProposalJudgeService delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingKgProposalJudgeService( final KgProposalJudgeService delegate,
                                            final LlmActivityLog log,
                                            final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public JudgeVerdict judge( final KgProposal proposal ) {
        final LlmCall call = log.begin( Subsystem.PROPOSAL_JUDGE, backend, model, "chat",
                                        describe( proposal ) );
        try {
            final JudgeVerdict verdict = delegate.judge( proposal );
            log.succeed( call, summarise( verdict ) );
            return verdict;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String describe( final KgProposal p ) {
        if ( p == null ) {
            return "null proposal";
        }
        return p.proposalType() + " " + p.id() + " " + p.proposedData();
    }

    private static String summarise( final JudgeVerdict v ) {
        if ( v == null ) {
            return "null verdict";
        }
        return "verdict=" + v.verdict() + " confidence=" + v.confidence()
             + " — " + v.rationale();
    }
}

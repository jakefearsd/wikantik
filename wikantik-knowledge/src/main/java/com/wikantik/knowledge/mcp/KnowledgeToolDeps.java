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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.citation.CitationRepository;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.ontology.OntologyModelManager;

import java.util.function.Supplier;

/**
 * Collaborator bundle for {@link KnowledgeMcpInitializer#assembleTools(KnowledgeToolDeps)}.
 * Every collaborator is optional (null = that tool group is not wired); the two log
 * services are {@link Supplier}s because they are set during engine startup and must be
 * resolved at call time to avoid startup-ordering coupling.
 *
 * <p>Built via {@link #builder()} so wiring sites name each dependency explicitly
 * instead of threading a long positional parameter list.</p>
 */
final class KnowledgeToolDeps {

    private final KnowledgeGraphService kgService;
    private final MentionIndex mentionIndex;
    private final NodeMentionSimilarity similarity;
    private final ContextRetrievalService ctxService;
    private final PageManager pageManager;
    private final StructuralIndexService structuralIndex;
    private final ForAgentProjectionService forAgent;
    private final BundleAssemblyService bundleService;
    private final BriefingAssemblyService briefingService;
    private final OntologyModelManager ontoMgr;
    private final CitationRepository citationRepo;
    private final Supplier< QueryLogService > queryLog;
    private final Supplier< BriefingLogService > briefingLog;
    private final PageViewGate viewGate;

    private KnowledgeToolDeps( final Builder b ) {
        this.kgService = b.kgService;
        this.mentionIndex = b.mentionIndex;
        this.similarity = b.similarity;
        this.ctxService = b.ctxService;
        this.pageManager = b.pageManager;
        this.structuralIndex = b.structuralIndex;
        this.forAgent = b.forAgent;
        this.bundleService = b.bundleService;
        this.briefingService = b.briefingService;
        this.ontoMgr = b.ontoMgr;
        this.citationRepo = b.citationRepo;
        this.queryLog = b.queryLog != null ? b.queryLog : () -> null;
        this.briefingLog = b.briefingLog != null ? b.briefingLog : () -> null;
        this.viewGate = b.viewGate != null ? b.viewGate : PageViewGate.ALLOW_ALL;
    }

    static Builder builder() {
        return new Builder();
    }

    KnowledgeGraphService kgService() { return kgService; }
    MentionIndex mentionIndex() { return mentionIndex; }
    NodeMentionSimilarity similarity() { return similarity; }
    ContextRetrievalService ctxService() { return ctxService; }
    PageManager pageManager() { return pageManager; }
    StructuralIndexService structuralIndex() { return structuralIndex; }
    ForAgentProjectionService forAgent() { return forAgent; }
    BundleAssemblyService bundleService() { return bundleService; }
    BriefingAssemblyService briefingService() { return briefingService; }
    OntologyModelManager ontoMgr() { return ontoMgr; }
    CitationRepository citationRepo() { return citationRepo; }
    Supplier< QueryLogService > queryLog() { return queryLog; }
    Supplier< BriefingLogService > briefingLog() { return briefingLog; }
    PageViewGate viewGate() { return viewGate; }

    static final class Builder {
        private KnowledgeGraphService kgService;
        private MentionIndex mentionIndex;
        private NodeMentionSimilarity similarity;
        private ContextRetrievalService ctxService;
        private PageManager pageManager;
        private StructuralIndexService structuralIndex;
        private ForAgentProjectionService forAgent;
        private BundleAssemblyService bundleService;
        private BriefingAssemblyService briefingService;
        private OntologyModelManager ontoMgr;
        private CitationRepository citationRepo;
        private Supplier< QueryLogService > queryLog;
        private Supplier< BriefingLogService > briefingLog;
        private PageViewGate viewGate;

        Builder kgService( final KnowledgeGraphService v ) { this.kgService = v; return this; }
        Builder mentionIndex( final MentionIndex v ) { this.mentionIndex = v; return this; }
        Builder similarity( final NodeMentionSimilarity v ) { this.similarity = v; return this; }
        Builder ctxService( final ContextRetrievalService v ) { this.ctxService = v; return this; }
        Builder pageManager( final PageManager v ) { this.pageManager = v; return this; }
        Builder structuralIndex( final StructuralIndexService v ) { this.structuralIndex = v; return this; }
        Builder forAgent( final ForAgentProjectionService v ) { this.forAgent = v; return this; }
        Builder bundleService( final BundleAssemblyService v ) { this.bundleService = v; return this; }
        Builder briefingService( final BriefingAssemblyService v ) { this.briefingService = v; return this; }
        Builder ontoMgr( final OntologyModelManager v ) { this.ontoMgr = v; return this; }
        Builder citationRepo( final CitationRepository v ) { this.citationRepo = v; return this; }
        Builder queryLog( final Supplier< QueryLogService > v ) { this.queryLog = v; return this; }
        Builder briefingLog( final Supplier< BriefingLogService > v ) { this.briefingLog = v; return this; }
        Builder viewGate( final PageViewGate v ) { this.viewGate = v; return this; }

        KnowledgeToolDeps build() {
            return new KnowledgeToolDeps( this );
        }
    }
}

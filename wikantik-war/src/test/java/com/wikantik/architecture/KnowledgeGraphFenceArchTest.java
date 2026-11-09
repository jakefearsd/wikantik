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
package com.wikantik.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.regex.Pattern;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Fence around the retired Knowledge Graph (step 1 of its removal, 2026-11-08 — see
 * docs/superpowers/plans/2026-11-08-kg-retirement-pass.md).
 *
 * <p>The KG (LLM-extracted entities: {@code kg_nodes}/{@code kg_edges}/{@code kg_proposals},
 * mentions, extraction, judge, curation, inclusion policy, hub discovery) is off by default
 * behind {@code wikantik.knowledge.enabled} and has no UI. Its code stays until the deletion
 * release. This rule is <b>frozen</b>: every dependency from non-KG code onto a KG class that
 * existed on the retirement day is recorded in {@code src/test/resources/archunit_store}, and any
 * <em>new</em> one fails the build. The store is the burn-down list for the deletion release —
 * entries only ever come out.</p>
 *
 * <p>Not KG, despite the names: {@code kg_content_chunks}/{@code content_chunk_embeddings} and
 * their chunking classes, the context bundle, briefing, agent projection, query log and eval
 * (all under {@code com.wikantik.knowledge}), and {@code DefaultContextRetrievalService}.</p>
 *
 * <p>Lives in {@code wikantik-war} because its test classpath reaches every module.</p>
 */
@AnalyzeClasses(
    packages = "com.wikantik",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class KnowledgeGraphFenceArchTest {

    private static final String NESTED = "(\\$.*)?";

    /** Fully-qualified class-name patterns that make up the retired Knowledge Graph. */
    static final List< Pattern > KG_CLASSES = List.of(
        Pattern.compile( "com\\.wikantik\\.knowledge\\.(DefaultKnowledgeGraphService|GraphRoleClassifier"
            + "|HdbscanClusterer|Hub\\w+|Kg\\w+|MentionIndex|NodeTextAssembler)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.knowledge\\.(curation|judge|extraction)\\..*" ),
        Pattern.compile( "com\\.wikantik\\.knowledge\\.embedding\\.(KgNodeEmbedding\\w+|NodeMentionSimilarity)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.kgpolicy\\..*" ),
        Pattern.compile( "com\\.wikantik\\.api\\.kgpolicy\\..*" ),
        Pattern.compile( "com\\.wikantik\\.api\\.knowledge\\.(KnowledgeGraphService|Kg\\w+|Tier|EntityExtractor"
            + "|Extracted\\w+|Extraction(Chunk|Context|Result)|PageExtract\\w+|PageKnowledgeSlice|ProposalJudge"
            + "|ProposalConflictFlags|Proposed\\w+|ConsolidatedProposal|JudgeContext|JudgeVerdict|Verdict"
            + "|NodeMention|NodeSignature|EdgeSignature|GraphSnapshot|Snapshot\\w+|SchemaDescription"
            + "|TraversalResult|SupportEvidence|Provenance|RelationshipTypeVocabulary|EntityTypeVocabulary)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.mcp\\.tools\\.kg\\..*" ),
        Pattern.compile( "com\\.wikantik\\.knowledge\\.mcp\\.(DiscoverSchemaTool|GetNodeTool|TraverseTool"
            + "|FindSimilarTool)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.mcp\\.tools\\.(CurateEdgesTool|CurateNodesTool|InspectProposalsTool"
            + "|ListOrphanedKgNodesTool|ListProposalsTool|ProposeKnowledgeTool|ReviewProposalsTool)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.rest\\.(AdminKnowledgeResource|AdminKgPolicyResource"
            + "|PageKnowledgeResource|KnowledgeGraphResource|AdminHubDiscoveryResource|AdminExtractionResource"
            + "|KnowledgeJsonMapper)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.rest\\.knowledge\\.(Kg\\w+|AdminKnowledgeIo)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.ontology\\.projection\\.(EntityProjector|EdgeProjector)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.ontology\\.runtime\\.(OntologyEntitySync|KgChangeEventListener)" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.event\\.KgChangeEvent" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.plugin\\.RelationshipsPlugin" + NESTED ),
        Pattern.compile( "com\\.wikantik\\.extractcli\\.(BootstrapExtractionCli|KgPolicyCli"
            + "|MaterializeApprovedProposalsCli|JudgeExperimentCli)" + NESTED ) );

    static final DescribedPredicate< JavaClass > KNOWLEDGE_GRAPH =
        DescribedPredicate.describe( "belong to the retired Knowledge Graph",
            c -> KG_CLASSES.stream().anyMatch( p -> p.matcher( c.getName() ).matches() ) );

    @ArchTest
    static final ArchRule no_new_dependencies_on_the_knowledge_graph = freeze(
        noClasses().that( not( KNOWLEDGE_GRAPH ) )
            .should().dependOnClassesThat( KNOWLEDGE_GRAPH )
            // The .as() text is the FreezingArchRule violation-store KEY — changing it orphans the store.
            .as( "Non-KG code must not gain new dependencies on the retired Knowledge Graph" ) );
}

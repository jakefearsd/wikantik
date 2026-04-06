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
package com.wikantik.knowledge;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.ComplExModel.Prediction;
import com.wikantik.knowledge.ComplExModel.Triple;
import com.wikantik.knowledge.TfidfModel.ScoredPair;
import com.wikantik.knowledge.TfidfModel.SimilarPagePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the ComplEx knowledge graph embedding model lifecycle: training, scheduled
 * retraining, and query methods for similarity, link prediction, and anomaly detection.
 *
 * <p>The model is held in memory and swapped atomically on retrain. At typical wiki graph
 * sizes (1K nodes), memory usage is ~400KB and training completes in milliseconds.</p>
 *
 * @since 1.0
 */
public class EmbeddingService {

    private static final Logger LOG = LogManager.getLogger( EmbeddingService.class );

    /** Configuration property keys. */
    public static final String PROP_DIMENSION = "wikantik.kge.dimension";
    public static final String PROP_EPOCHS = "wikantik.kge.epochs";
    public static final String PROP_LEARNING_RATE = "wikantik.kge.learningRate";
    public static final String PROP_NEG_SAMPLES = "wikantik.kge.negSamples";
    public static final String PROP_MARGIN = "wikantik.kge.margin";
    public static final String PROP_RETRAIN_MINUTES = "wikantik.kge.retrainMinutes";

    /** A scored edge prediction. */
    public record EdgePrediction( String sourceName, String relationshipType, String targetName, double score ) {}

    /** A pair of nodes that may be duplicates (structural similarity only). */
    public record MergeCandidate( String nameA, String nameB, double similarity ) {}

    /** A pair of nodes with both structural and content similarity scores. */
    public record EnhancedMergeCandidate( String nameA, String nameB,
                                           double structural, double content, double combined ) {}

    /** Status of the structural and content embedding models. */
    public record Status( int modelVersion, int dimension, int entityCount, int relationCount,
                          Instant lastTrained, boolean training,
                          boolean contentReady, int contentDimension, int contentEntityCount,
                          Instant contentLastTrained, boolean contentTraining ) {}

    /** Content similarity result. */
    public record ContentSimilarity( String name, double similarity ) {}

    private final JdbcKnowledgeRepository kgRepo;
    private final EmbeddingRepository embeddingRepo;
    private final ContentEmbeddingRepository contentEmbeddingRepo;
    private final PageManager pageManager;
    private final AtomicReference< ComplExModel > currentModel = new AtomicReference<>();
    private final AtomicReference< TfidfModel > currentContentModel = new AtomicReference<>();
    private final AtomicReference< Instant > lastTrained = new AtomicReference<>();
    private final AtomicReference< Instant > contentLastTrained = new AtomicReference<>();
    private volatile int modelVersion = 0;
    private volatile int contentModelVersion = 0;
    private volatile boolean training = false;
    private volatile boolean contentTraining = false;
    private ScheduledExecutorService scheduler;

    // Hyperparameters (defaults, overridable via properties)
    private int dimension = 50;
    private int epochs = 100;
    private float learningRate = 0.01f;
    private int negSamples = 10;
    private float margin = 1.0f;

    // Mapping from entity name to kg_nodes UUID (for storage)
    private final Map< String, UUID > entityUuids = new ConcurrentHashMap<>();

    // Set of existing triples for filtering predictions
    private final Set< String > existingTriples = ConcurrentHashMap.newKeySet();

    public EmbeddingService( final JdbcKnowledgeRepository kgRepo,
                             final EmbeddingRepository embeddingRepo,
                             final ContentEmbeddingRepository contentEmbeddingRepo,
                             final PageManager pageManager ) {
        this.kgRepo = kgRepo;
        this.embeddingRepo = embeddingRepo;
        this.contentEmbeddingRepo = contentEmbeddingRepo;
        this.pageManager = pageManager;

        // Try to load previously trained models
        final ComplExModel saved = embeddingRepo.loadLatestModel();
        if( saved != null ) {
            currentModel.set( saved );
            modelVersion = embeddingRepo.getLatestModelVersion();
            LOG.info( "Loaded saved ComplEx model (version {}, {} entities)", modelVersion, saved.getEntityCount() );
        }
        final TfidfModel savedContent = contentEmbeddingRepo.loadLatestModel();
        if( savedContent != null ) {
            currentContentModel.set( savedContent );
            contentModelVersion = contentEmbeddingRepo.getLatestModelVersion();
            LOG.info( "Loaded saved content model (version {}, {} entities)", contentModelVersion, savedContent.getEntityCount() );
        }
    }

    /** Applies configuration from wiki properties. */
    public void configure( final Properties props ) {
        dimension = Integer.parseInt( props.getProperty( PROP_DIMENSION, "50" ) );
        epochs = Integer.parseInt( props.getProperty( PROP_EPOCHS, "100" ) );
        learningRate = Float.parseFloat( props.getProperty( PROP_LEARNING_RATE, "0.01" ) );
        negSamples = Integer.parseInt( props.getProperty( PROP_NEG_SAMPLES, "10" ) );
        margin = Float.parseFloat( props.getProperty( PROP_MARGIN, "1.0" ) );
    }

    /** Starts periodic retraining on a background thread. */
    public void startPeriodicRetraining( final long intervalMinutes ) {
        if( scheduler != null ) return;
        scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "kge-retrain" );
            t.setDaemon( true );
            return t;
        } );
        scheduler.scheduleAtFixedRate( () -> {
            try {
                retrain();
            } catch( final Exception e ) {
                LOG.warn( "Scheduled KGE retrain failed", e );
            }
            try {
                retrainContentModel();
            } catch( final Exception e ) {
                LOG.warn( "Scheduled content model retrain failed", e );
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES );
        LOG.info( "KGE periodic retraining scheduled every {} minutes", intervalMinutes );
    }

    /** Stops periodic retraining. */
    public void shutdown() {
        if( scheduler != null ) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Retrains the ComplEx model on the current graph.
     * Safe to call from multiple threads — only one train runs at a time.
     */
    public synchronized void retrain() {
        if( training ) return;
        training = true;
        try {
            final long start = System.currentTimeMillis();

            // Load all nodes
            final List< KgNode > nodes = kgRepo.queryNodes( null, null, 100_000, 0 );
            if( nodes.size() < 2 ) {
                LOG.info( "Skipping KGE training: only {} nodes in graph", nodes.size() );
                return;
            }

            // Build entity index
            final List< String > entityNames = new ArrayList<>();
            entityUuids.clear();
            for( final KgNode node : nodes ) {
                entityNames.add( node.name() );
                entityUuids.put( node.name(), node.id() );
            }

            // Load all edges
            final List< KgEdge > edges = kgRepo.getAllEdges();
            if( edges.isEmpty() ) {
                LOG.info( "Skipping KGE training: no edges in graph" );
                return;
            }

            // Build relation index and name-to-UUID maps
            final Map< String, Integer > entityIdx = new HashMap<>();
            for( int i = 0; i < entityNames.size(); i++ ) entityIdx.put( entityNames.get( i ), i );

            final Map< UUID, String > uuidToName = new HashMap<>();
            for( final KgNode n : nodes ) uuidToName.put( n.id(), n.name() );

            final Set< String > relTypes = new LinkedHashSet<>();
            for( final KgEdge e : edges ) relTypes.add( e.relationshipType() );
            final List< String > relationNames = new ArrayList<>( relTypes );
            final Map< String, Integer > relIdx = new HashMap<>();
            for( int i = 0; i < relationNames.size(); i++ ) relIdx.put( relationNames.get( i ), i );

            // Build triples and existing-triple set
            final List< Triple > triples = new ArrayList<>();
            existingTriples.clear();
            for( final KgEdge e : edges ) {
                final String srcName = uuidToName.get( e.sourceId() );
                final String tgtName = uuidToName.get( e.targetId() );
                if( srcName == null || tgtName == null ) continue;
                final Integer src = entityIdx.get( srcName );
                final Integer tgt = entityIdx.get( tgtName );
                final Integer rel = relIdx.get( e.relationshipType() );
                if( src == null || tgt == null || rel == null ) continue;
                triples.add( new Triple( src, rel, tgt ) );
                existingTriples.add( tripleKey( srcName, e.relationshipType(), tgtName ) );
            }

            // Train
            final ComplExModel model = new ComplExModel();
            model.train( entityNames, relationNames, triples, dimension, epochs,
                learningRate, negSamples, margin );

            // Persist and swap ComplEx model
            modelVersion++;
            embeddingRepo.saveEmbeddings( modelVersion, model, entityUuids );
            embeddingRepo.deleteOldVersions( modelVersion );
            currentModel.set( model );
            lastTrained.set( Instant.now() );

            final long complexElapsed = System.currentTimeMillis() - start;
            LOG.info( "ComplEx trained in {}ms: {} entities, {} relations, {} triples (version {})",
                complexElapsed, entityNames.size(), relationNames.size(), triples.size(), modelVersion );
        } finally {
            training = false;
        }
    }

    /** Returns the current model status (both structural and content). */
    public Status getStatus() {
        final ComplExModel m = currentModel.get();
        final TfidfModel cm = currentContentModel.get();
        return new Status(
            modelVersion,
            m != null ? m.getDimension() : dimension,
            m != null ? m.getEntityCount() : 0,
            m != null ? m.getRelationCount() : 0,
            lastTrained.get(),
            training,
            cm != null,
            cm != null ? cm.getDimension() : TfidfModel.DIMENSION,
            cm != null ? cm.getEntityCount() : 0,
            contentLastTrained.get(),
            contentTraining
        );
    }

    /** Returns true if a trained model is available. */
    public boolean isReady() {
        return currentModel.get() != null;
    }

    /**
     * Finds the most similar nodes to the given node name.
     *
     * @return similar nodes with similarity scores, or empty if model unavailable
     */
    public List< Prediction > getSimilarNodes( final String nodeName, final int limit ) {
        final ComplExModel m = currentModel.get();
        if( m == null ) return List.of();
        final int id = m.entityId( nodeName );
        if( id < 0 ) return List.of();
        return m.mostSimilar( id, limit );
    }

    /**
     * Predicts missing edges in the graph. Returns the top-K highest-scoring absent triples.
     */
    public List< EdgePrediction > predictMissingEdges( final int topK ) {
        final ComplExModel m = currentModel.get();
        if( m == null ) return List.of();

        final PriorityQueue< EdgePrediction > pq = new PriorityQueue<>(
            Comparator.comparingDouble( EdgePrediction::score ) );

        for( int r = 0; r < m.getRelationCount(); r++ ) {
            for( int h = 0; h < m.getEntityCount(); h++ ) {
                final List< Prediction > tails = m.predictTails( h, r, Math.min( topK, 10 ) );
                for( final Prediction p : tails ) {
                    final String src = m.getEntityNames().get( h );
                    final String rel = m.getRelationNames().get( r );
                    final String tgt = p.entityName();
                    if( existingTriples.contains( tripleKey( src, rel, tgt ) ) ) continue;

                    final EdgePrediction ep = new EdgePrediction( src, rel, tgt, p.score() );
                    if( pq.size() < topK ) {
                        pq.add( ep );
                    } else if( p.score() > pq.peek().score() ) {
                        pq.poll();
                        pq.add( ep );
                    }
                }
            }
        }

        final List< EdgePrediction > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( EdgePrediction::score ).reversed() );
        return result;
    }

    /**
     * Predicts missing edges for a specific node.
     */
    public List< EdgePrediction > predictEdgesForNode( final String nodeName, final int topK ) {
        final ComplExModel m = currentModel.get();
        if( m == null ) return List.of();
        final int h = m.entityId( nodeName );
        if( h < 0 ) return List.of();

        final PriorityQueue< EdgePrediction > pq = new PriorityQueue<>(
            Comparator.comparingDouble( EdgePrediction::score ) );

        for( int r = 0; r < m.getRelationCount(); r++ ) {
            final List< Prediction > tails = m.predictTails( h, r, topK + 5 );
            for( final Prediction p : tails ) {
                final String rel = m.getRelationNames().get( r );
                if( existingTriples.contains( tripleKey( nodeName, rel, p.entityName() ) ) ) continue;

                final EdgePrediction ep = new EdgePrediction( nodeName, rel, p.entityName(), p.score() );
                if( pq.size() < topK ) {
                    pq.add( ep );
                } else if( p.score() > pq.peek().score() ) {
                    pq.poll();
                    pq.add( ep );
                }
            }
        }

        final List< EdgePrediction > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( EdgePrediction::score ).reversed() );
        return result;
    }

    /**
     * Returns existing edges with the lowest plausibility scores (potential anomalies).
     */
    public List< EdgePrediction > getAnomalousEdges( final int topK ) {
        final ComplExModel m = currentModel.get();
        if( m == null ) return List.of();

        final PriorityQueue< EdgePrediction > pq = new PriorityQueue<>(
            Comparator.comparingDouble( ( EdgePrediction ep ) -> ep.score() ).reversed() );

        // Score all existing edges
        final List< KgEdge > edges = kgRepo.getAllEdges();
        final Map< UUID, String > names = new HashMap<>();
        for( final KgEdge e : edges ) {
            names.put( e.sourceId(), null );
            names.put( e.targetId(), null );
        }
        final Map< UUID, String > resolved = kgRepo.getNodeNames( names.keySet() );

        for( final KgEdge e : edges ) {
            final String src = resolved.get( e.sourceId() );
            final String tgt = resolved.get( e.targetId() );
            if( src == null || tgt == null ) continue;
            final int hId = m.entityId( src );
            final int rId = m.relationId( e.relationshipType() );
            final int tId = m.entityId( tgt );
            if( hId < 0 || rId < 0 || tId < 0 ) continue;

            final double s = m.score( hId, rId, tId );
            final EdgePrediction ep = new EdgePrediction( src, e.relationshipType(), tgt, s );
            if( pq.size() < topK ) {
                pq.add( ep );
            } else if( s < pq.peek().score() ) {
                pq.poll();
                pq.add( ep );
            }
        }

        final List< EdgePrediction > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( EdgePrediction::score ) ); // ascending — worst first
        return result;
    }

    /**
     * Returns pairs of nodes with high embedding similarity (merge candidates).
     */
    public List< MergeCandidate > getMergeCandidates( final int topK, final double minSimilarity ) {
        final ComplExModel m = currentModel.get();
        if( m == null ) return List.of();

        final PriorityQueue< MergeCandidate > pq = new PriorityQueue<>(
            Comparator.comparingDouble( MergeCandidate::similarity ) );

        for( int i = 0; i < m.getEntityCount(); i++ ) {
            for( int j = i + 1; j < m.getEntityCount(); j++ ) {
                final double sim = m.similarity( i, j );
                if( sim < minSimilarity ) continue;
                final MergeCandidate mc = new MergeCandidate(
                    m.getEntityNames().get( i ), m.getEntityNames().get( j ), sim );
                if( pq.size() < topK ) {
                    pq.add( mc );
                } else if( sim > pq.peek().similarity() ) {
                    pq.poll();
                    pq.add( mc );
                }
            }
        }

        final List< MergeCandidate > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( MergeCandidate::similarity ).reversed() );
        return result;
    }

    /**
     * Returns content-similar nodes based on TF-IDF text embeddings.
     */
    public List< ContentSimilarity > getContentSimilarNodes( final String nodeName, final int limit ) {
        final TfidfModel cm = currentContentModel.get();
        if( cm == null ) return List.of();
        final int id = cm.entityId( nodeName );
        if( id < 0 ) return List.of();
        return cm.mostSimilar( id, limit ).stream()
            .map( sp -> new ContentSimilarity( sp.name(), sp.score() ) )
            .toList();
    }

    /** Returns true if the content model is trained and ready. */
    public boolean isContentReady() {
        return currentContentModel.get() != null;
    }

    /**
     * Returns merge candidates with both structural and content similarity scores.
     * Sorted by combined score descending.
     */
    public List< EnhancedMergeCandidate > getMergeCandidatesEnhanced( final int topK,
                                                                       final double minSimilarity ) {
        final ComplExModel m = currentModel.get();
        final TfidfModel cm = currentContentModel.get();
        if( m == null ) return List.of();

        final PriorityQueue< EnhancedMergeCandidate > pq = new PriorityQueue<>(
            Comparator.comparingDouble( EnhancedMergeCandidate::combined ) );

        for( int i = 0; i < m.getEntityCount(); i++ ) {
            for( int j = i + 1; j < m.getEntityCount(); j++ ) {
                final double structural = m.similarity( i, j );
                double content = 0;
                if( cm != null ) {
                    final int ci = cm.entityId( m.getEntityNames().get( i ) );
                    final int cj = cm.entityId( m.getEntityNames().get( j ) );
                    if( ci >= 0 && cj >= 0 ) {
                        content = cm.similarity( ci, cj );
                    }
                }
                final double combined = 0.5 * structural + 0.5 * content;
                if( combined < minSimilarity ) continue;

                final EnhancedMergeCandidate mc = new EnhancedMergeCandidate(
                    m.getEntityNames().get( i ), m.getEntityNames().get( j ),
                    structural, content, combined );
                if( pq.size() < topK ) {
                    pq.add( mc );
                } else if( combined > pq.peek().combined() ) {
                    pq.poll();
                    pq.add( mc );
                }
            }
        }

        final List< EnhancedMergeCandidate > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( EnhancedMergeCandidate::combined ).reversed() );
        return result;
    }

    // ---- Content model training ----

    /**
     * Retrains the TF-IDF content model on ALL wiki pages (not just KG nodes).
     * Each page's text is stripped of markdown and vectorized. Pages that are also
     * KG nodes get their entity_id stored; others have null entity_id.
     */
    public synchronized void retrainContentModel() {
        if( pageManager == null ) {
            LOG.warn( "Cannot retrain content model: no PageManager available" );
            return;
        }
        if( contentTraining ) return;
        contentTraining = true;
        try {
            final long start = System.currentTimeMillis();

            final Collection< Page > allPages = pageManager.getAllPages();
            final List< String > names = new ArrayList<>( allPages.size() );
            final List< String > documents = new ArrayList<>( allPages.size() );

            for( final Page page : allPages ) {
                final String pageName = page.getName();
                try {
                    final String text = pageManager.getPureText( page );
                    final String stripped = NodeTextAssembler.stripMarkdown( text != null ? text : "" );

                    // Build document: page name boosted 3x + stripped body
                    final StringBuilder doc = new StringBuilder();
                    doc.append( pageName ).append( ' ' ).append( pageName ).append( ' ' ).append( pageName );
                    if( !stripped.isBlank() ) {
                        doc.append( ' ' ).append( stripped );
                    }

                    names.add( pageName );
                    documents.add( doc.toString() );
                } catch( final Exception e ) {
                    LOG.warn( "Failed to load page text for '{}': {}", pageName, e.getMessage() );
                }
            }

            if( names.isEmpty() ) {
                LOG.info( "No pages found for content model training" );
                return;
            }

            final TfidfModel contentModel = new TfidfModel();
            contentModel.build( names, documents );

            // Build entity UUID map — pages with KG nodes get their UUID, others get null
            final Map< String, UUID > pageUuids = new HashMap<>( entityUuids );

            contentModelVersion++;
            contentEmbeddingRepo.saveEmbeddings( contentModelVersion, contentModel, pageUuids );
            contentEmbeddingRepo.deleteOldVersions( contentModelVersion );
            currentContentModel.set( contentModel );
            contentLastTrained.set( Instant.now() );

            final long elapsed = System.currentTimeMillis() - start;
            LOG.info( "TF-IDF content model trained in {}ms: {} pages, dim {} (version {})",
                elapsed, names.size(), TfidfModel.DIMENSION, contentModelVersion );
        } catch( final Exception e ) {
            LOG.warn( "Content model training failed: {}", e.getMessage() );
        } finally {
            contentTraining = false;
        }
    }

    /**
     * Returns the top-K most similar page pairs across the entire content corpus.
     */
    public List< SimilarPagePair > getTopSimilarPagePairs( final int limit ) {
        final TfidfModel cm = currentContentModel.get();
        if( cm == null ) return List.of();
        return cm.topSimilarPairs( limit );
    }

    private static String tripleKey( final String src, final String rel, final String tgt ) {
        return src + "|" + rel + "|" + tgt;
    }
}

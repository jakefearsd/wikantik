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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractedMention;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.ProposedEdge;
import com.wikantik.api.knowledge.ProposedNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates the JSON blob produced by an LLM extractor into an
 * {@link ExtractionResult}. Shared between Claude and Ollama so the two
 * backends are interchangeable at the contract level.
 *
 * <p>Poisoned output (malformed JSON, missing keys, wrong types) is logged and
 * translated to an empty result — callers must not propagate the exception up
 * the event path.
 */
public final class ExtractionResponseParser {

    private static final Logger LOG = LogManager.getLogger( ExtractionResponseParser.class );
    private static final Gson GSON = new Gson();

    private ExtractionResponseParser() {}

    /**
     * @param rawJson              text returned by the LLM; may wrap in fences
     * @param chunk                chunk this response applies to
     * @param context              context passed to the extractor (used for new-node detection)
     * @param extractorCode        value for {@link ExtractionResult#extractorCode()}
     * @param latency              measured wall-time of the call
     * @param confidenceThreshold  proposals below this are dropped (mentions are kept)
     */
    public static ExtractionResult parse( final String rawJson,
                                          final ExtractionChunk chunk,
                                          final ExtractionContext context,
                                          final String extractorCode,
                                          final Duration latency,
                                          final double confidenceThreshold ) {
        final String stripped = stripJsonFence( rawJson );
        if( stripped == null || stripped.isBlank() ) {
            return ExtractionResult.empty( extractorCode, latency );
        }

        final JsonObject root;
        try {
            final JsonElement parsed = JsonParser.parseString( stripped );
            if( !parsed.isJsonObject() ) {
                LOG.warn( "Extractor {} returned non-object JSON for chunk {}", extractorCode, chunk.id() );
                return ExtractionResult.empty( extractorCode, latency );
            }
            root = parsed.getAsJsonObject();
        } catch( final RuntimeException e ) {
            LOG.warn( "Extractor {} returned malformed JSON for chunk {}: {}",
                      extractorCode, chunk.id(), e.getMessage() );
            return ExtractionResult.empty( extractorCode, latency );
        }

        final Set< String > knownNames = new HashSet<>();
        for( final var n : context.existingNodes() ) {
            knownNames.add( n.name().toLowerCase( Locale.ROOT ) );
        }

        final List< ExtractedMention > mentions = new ArrayList<>();
        final List< ProposedNode > nodes = new ArrayList<>();
        final Set< String > mentionKeys = new HashSet<>();
        final Set< String > knownEntityNames = new HashSet<>();

        final JsonElement entitiesEl = root.get( "entities" );
        if( entitiesEl != null && entitiesEl.isJsonArray() ) {
            for( final JsonElement raw : entitiesEl.getAsJsonArray() ) {
                if( !raw.isJsonObject() ) {
                    continue;
                }
                final JsonObject e = raw.getAsJsonObject();
                final String name = stringOrNull( e, "name" );
                if( name == null || name.isBlank() ) {
                    continue;
                }
                knownEntityNames.add( name.toLowerCase( Locale.ROOT ) );

                final String type = stringOrNull( e, "type" );
                final double conf = numberOr( e, "confidence", 0.0 );
                final String reasoning = stringOrNull( e, "reasoning" );

                final String mentionKey = name.toLowerCase( Locale.ROOT );
                if( mentionKeys.add( mentionKey ) ) {
                    mentions.add( new ExtractedMention( chunk.id(), name, clamp( conf ) ) );
                }

                if( !knownNames.contains( mentionKey ) && conf >= confidenceThreshold ) {
                    nodes.add( new ProposedNode(
                        name,
                        type == null ? "Concept" : type,
                        Map.of(),
                        clamp( conf ),
                        reasoning == null ? "" : reasoning ) );
                }
            }
        }

        final List< ProposedEdge > edges = new ArrayList<>();
        final JsonElement relationsEl = root.get( "relations" );
        if( relationsEl != null && relationsEl.isJsonArray() ) {
            for( final JsonElement raw : relationsEl.getAsJsonArray() ) {
                if( !raw.isJsonObject() ) {
                    continue;
                }
                final JsonObject r = raw.getAsJsonObject();
                final String src = stringOrNull( r, "source" );
                final String tgt = stringOrNull( r, "target" );
                final String type = stringOrNull( r, "type" );
                final double conf = numberOr( r, "confidence", 0.0 );
                final String reasoning = stringOrNull( r, "reasoning" );
                if( src == null || tgt == null || type == null
                    || src.isBlank() || tgt.isBlank() || type.isBlank() ) {
                    continue;
                }
                // Enforce that both ends are grounded in the entities the extractor itself produced.
                if( !knownEntityNames.contains( src.toLowerCase( Locale.ROOT ) )
                    || !knownEntityNames.contains( tgt.toLowerCase( Locale.ROOT ) ) ) {
                    continue;
                }
                if( conf < confidenceThreshold ) {
                    continue;
                }
                edges.add( new ProposedEdge(
                    src, tgt, type, Map.of(),
                    clamp( conf ),
                    reasoning == null ? "" : reasoning ) );
            }
        }

        return new ExtractionResult( nodes, edges, mentions, extractorCode, latency );
    }

    /**
     * Strips common wrapping patterns that LLMs produce even when asked for raw JSON:
     * leading text, markdown code fences, trailing chatter.
     */
    static String stripJsonFence( final String raw ) {
        if( raw == null ) {
            return null;
        }
        String s = raw.trim();
        if( s.startsWith( "```" ) ) {
            final int nl = s.indexOf( '\n' );
            if( nl > 0 ) {
                s = s.substring( nl + 1 );
            }
            if( s.endsWith( "```" ) ) {
                s = s.substring( 0, s.length() - 3 );
            }
            s = s.trim();
        }
        final int firstBrace = s.indexOf( '{' );
        final int lastBrace = s.lastIndexOf( '}' );
        if( firstBrace >= 0 && lastBrace > firstBrace ) {
            return s.substring( firstBrace, lastBrace + 1 );
        }
        return s;
    }

    private static String stringOrNull( final JsonObject o, final String key ) {
        final JsonElement e = o.get( key );
        if( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) {
            return null;
        }
        return e.getAsString();
    }

    private static double numberOr( final JsonObject o, final String key, final double def ) {
        final JsonElement e = o.get( key );
        if( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) {
            return def;
        }
        try {
            return e.getAsDouble();
        } catch( final NumberFormatException ex ) {
            LOG.info( "Extraction payload had non-numeric '{}' for key '{}' — using default {}: {}",
                e, key, def, ex.getMessage() );
            return def;
        }
    }

    private static double clamp( final double v ) {
        if( Double.isNaN( v ) ) {
            return 0.0;
        }
        return Math.max( 0.0, Math.min( 1.0, v ) );
    }

    /**
     * Serializes an {@link ExtractionResult} back to the canonical JSON shape.
     * Primarily for fixture generation in tests — both extractors emit this
     * shape, and test fixtures can round-trip through it.
     */
    public static String toJson( final ExtractionResult r ) {
        return GSON.toJson( r );
    }
}

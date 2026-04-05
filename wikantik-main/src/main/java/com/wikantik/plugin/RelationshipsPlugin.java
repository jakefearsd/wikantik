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
package com.wikantik.plugin;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.util.TextUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders a page's knowledge graph relationships as navigable links.
 * Usage in wiki markup: {@code [{Relationships}]}
 *
 * <p>Outbound edges use the relationship type as-is (e.g., "Depends on").
 * Inbound edges use inverted labels (e.g., "depends-on" becomes "Dependency of").</p>
 *
 * <p>If the page has no node in the knowledge graph or no edges, renders nothing.</p>
 */
public class RelationshipsPlugin implements Plugin {

    private static final Map< String, String > INVERTED_LABELS = Map.of(
        "depends-on", "Dependency of",
        "part-of", "Parts",
        "enables", "Enabled by",
        "supersedes", "Superseded by",
        "related", "Related"
    );

    @Override
    public String execute( final Context context, final Map< String, String > params )
            throws PluginException {
        final KnowledgeGraphService service = context.getEngine()
                .getManager( KnowledgeGraphService.class );
        if( service == null ) {
            return "";
        }

        final String pageName = context.getRealPage().getName();
        final KgNode node = service.getNodeByName( pageName );
        if( node == null ) {
            return "";
        }

        final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
        if( edges.isEmpty() ) {
            return "";
        }

        // Batch-resolve all referenced node IDs to names
        final Set< UUID > refIds = new HashSet<>();
        edges.forEach( e -> {
            refIds.add( e.sourceId() );
            refIds.add( e.targetId() );
        } );
        final Map< UUID, String > nameMap = service.getNodeNames( refIds );

        // Group edges: outbound by relationship type, inbound by inverted label
        final Map< String, List< String > > grouped = new LinkedHashMap<>();

        for( final KgEdge edge : edges ) {
            final boolean outbound = edge.sourceId().equals( node.id() );
            final String relType = edge.relationshipType();
            final String label;
            final String targetName;

            if( outbound ) {
                label = formatLabel( relType );
                targetName = nameMap.getOrDefault( edge.targetId(), edge.targetId().toString() );
            } else {
                label = INVERTED_LABELS.getOrDefault( relType, formatLabel( relType ) );
                targetName = nameMap.getOrDefault( edge.sourceId(), edge.sourceId().toString() );
            }

            grouped.computeIfAbsent( label, k -> new ArrayList<>() ).add( targetName );
        }

        // Render HTML
        final StringBuilder html = new StringBuilder();
        html.append( "<div class=\"relationships-plugin\">" );
        html.append( "<strong>Relationships</strong>" );

        for( final Map.Entry< String, List< String > > entry : grouped.entrySet() ) {
            html.append( "<div class=\"relationship-group\">" );
            html.append( "<em>" ).append( entry.getKey() ).append( ":</em> " );
            html.append( entry.getValue().stream()
                    .map( name -> {
                        final String href = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), name );
                        return "<a href=\"" + href + "\">" + TextUtil.replaceEntities( name ) + "</a>";
                    } )
                    .collect( Collectors.joining( ", " ) ) );
            html.append( "</div>" );
        }

        html.append( "</div>" );
        return html.toString();
    }

    /**
     * Converts a kebab-case relationship type to a human-readable label.
     * e.g., "depends-on" -> "Depends on", "part-of" -> "Part of"
     */
    static String formatLabel( final String relType ) {
        if( relType == null || relType.isEmpty() ) {
            return relType;
        }
        final String spaced = relType.replace( '-', ' ' );
        return Character.toUpperCase( spaced.charAt( 0 ) ) + spaced.substring( 1 );
    }
}

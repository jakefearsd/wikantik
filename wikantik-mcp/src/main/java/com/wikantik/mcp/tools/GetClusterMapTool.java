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
package com.wikantik.mcp.tools;


import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;

import java.util.*;

/**
 * Read-only MCP tool that returns a complete map of how the wiki is organized into clusters.
 * Replaces multiple discovery calls (list_pages, query_metadata, list_metadata_values)
 * with a single call.
 */
public class GetClusterMapTool implements McpTool {

    public static final String TOOL_NAME = "get_cluster_map";

    private static final Set< String > METADATA_FIELDS =
            Set.of( "type", "tags", "summary", "status", "date", "author", "cluster", "related" );

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;

    public GetClusterMapTool( final PageManager pageManager, final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "cluster", Map.of( "type", "string", "description",
                "Optional cluster name to scope the result to a single cluster" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get a complete map of how the wiki is organized into clusters. " +
                        "Returns totalPages, clusters (with hub, pages, sub-clusters), unclusteredPages, " +
                        "metadataConventions, and pageMetadata for every page. " +
                        "Optionally scope to a single cluster by name." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    /** Intermediate result from the page-scanning phase. */
    record ScanResult(
            Map< String, List< String > > clusterPages,
            Map< String, String > clusterHubs,
            List< String > unclustered,
            Map< String, Map< String, Object > > pageMetadataMap,
            Map< String, Set< String > > conventions
    ) {}

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String scopeCluster = McpToolUtils.getString( arguments, "cluster" );

        // Single pass over all pages: parse frontmatter, group by cluster
        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Failed to list pages: " + e.getMessage() );
        }

        final ScanResult scan = scanPages( allPages );

        // Build cluster tree from scan results
        final Map< String, Map< String, Object > > parentClusters = new LinkedHashMap<>();
        final Map< String, Map< String, Object > > subClusterMap = new LinkedHashMap<>();
        assembleClusters( scan.clusterPages(), scan.clusterHubs(), parentClusters, subClusterMap );

        // Scoped mode: filter to single cluster
        if ( scopeCluster != null ) {
            return buildScopedResult( scopeCluster, parentClusters, subClusterMap,
                    scan.pageMetadataMap(), allPages.size() );
        }

        // Build full result
        final List< String > unclustered = scan.unclustered();
        Collections.sort( unclustered );
        final Map< String, Object > conventionsSorted = new LinkedHashMap<>();
        for ( final Map.Entry< String, Set< String > > entry : scan.conventions().entrySet() ) {
            conventionsSorted.put( entry.getKey(), new ArrayList<>( entry.getValue() ) );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "totalPages", scan.pageMetadataMap().size() );
        result.put( "clusters", new ArrayList<>( parentClusters.values() ) );
        result.put( "unclusteredPages", unclustered );
        result.put( "metadataConventions", conventionsSorted );
        result.put( "pageMetadata", scan.pageMetadataMap() );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    /**
     * Scan all pages in a single pass: parse frontmatter, group by cluster,
     * collect per-page metadata summaries and metadata conventions.
     */
    ScanResult scanPages( final Collection< Page > allPages ) {
        final Map< String, List< String > > clusterPages = new LinkedHashMap<>();
        final Map< String, String > clusterHubs = new LinkedHashMap<>();
        final List< String > unclustered = new ArrayList<>();
        final Map< String, Map< String, Object > > pageMetadataMap = new LinkedHashMap<>();
        final Map< String, Set< String > > conventions = new LinkedHashMap<>();

        for ( final Page page : allPages ) {
            final String pageName = page.getName();
            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                continue;
            }

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > metadata = parsed.metadata();

            // Build per-page metadata summary (only the 8 standard fields)
            final Map< String, Object > pageMeta = new LinkedHashMap<>();
            for ( final String field : METADATA_FIELDS ) {
                if ( metadata.containsKey( field ) ) {
                    pageMeta.put( field, metadata.get( field ) );
                }
            }
            pageMetadataMap.put( pageName, pageMeta );

            // Collect metadata conventions
            for ( final Map.Entry< String, Object > entry : metadata.entrySet() ) {
                conventions.computeIfAbsent( entry.getKey(), k -> new TreeSet<>() );
                final Object val = entry.getValue();
                if ( val instanceof String ) {
                    conventions.get( entry.getKey() ).add( ( String ) val );
                } else if ( val instanceof List ) {
                    for ( final Object item : ( List< ? > ) val ) {
                        if ( item instanceof String ) {
                            conventions.get( entry.getKey() ).add( ( String ) item );
                        }
                    }
                }
            }

            // Group by cluster
            final Object clusterVal = metadata.get( "cluster" );
            if ( clusterVal instanceof String && !( ( String ) clusterVal ).isBlank() ) {
                final String cluster = ( String ) clusterVal;
                clusterPages.computeIfAbsent( cluster, k -> new ArrayList<>() ).add( pageName );

                // Detect hub
                if ( "hub".equals( metadata.get( "type" ) ) ) {
                    clusterHubs.put( cluster, pageName );
                }
            } else {
                unclustered.add( pageName );
            }
        }

        return new ScanResult( clusterPages, clusterHubs, unclustered, pageMetadataMap, conventions );
    }

    /**
     * Build the parent/sub-cluster tree from the flat cluster-pages map.
     * Sub-clusters (names containing "/") are nested under their parent;
     * orphaned sub-clusters are promoted to top-level.
     */
    @SuppressWarnings( "unchecked" )
    void assembleClusters( final Map< String, List< String > > clusterPages,
                           final Map< String, String > clusterHubs,
                           final Map< String, Map< String, Object > > parentClusters,
                           final Map< String, Map< String, Object > > subClusterMap ) {
        for ( final Map.Entry< String, List< String > > entry : clusterPages.entrySet() ) {
            final String clusterName = entry.getKey();
            final List< String > pages = entry.getValue();
            Collections.sort( pages );

            final Map< String, Object > clusterObj = new LinkedHashMap<>();
            clusterObj.put( "name", clusterName );
            clusterObj.put( "hub", clusterHubs.get( clusterName ) );
            clusterObj.put( "pages", pages );
            clusterObj.put( "pageCount", pages.size() );
            clusterObj.put( "subClusters", new ArrayList<>() );

            if ( clusterName.contains( "/" ) ) {
                subClusterMap.put( clusterName, clusterObj );
            } else {
                parentClusters.put( clusterName, clusterObj );
            }
        }

        // Nest sub-clusters under their parents
        for ( final Map.Entry< String, Map< String, Object > > entry : subClusterMap.entrySet() ) {
            final String subName = entry.getKey();
            final String parentName = subName.substring( 0, subName.indexOf( '/' ) );
            final Map< String, Object > parent = parentClusters.get( parentName );
            if ( parent != null ) {
                final List< Map< String, Object > > subs =
                        ( List< Map< String, Object > > ) parent.get( "subClusters" );
                subs.add( entry.getValue() );
            } else {
                // Orphaned sub-cluster — treat as top-level
                parentClusters.put( subName, entry.getValue() );
            }
        }
    }

    private McpSchema.CallToolResult buildScopedResult( final String scopeCluster,
                                                          final Map< String, Map< String, Object > > parentClusters,
                                                          final Map< String, Map< String, Object > > subClusterMap,
                                                          final Map< String, Map< String, Object > > pageMetadataMap,
                                                          final int totalPages ) {
        // Find the requested cluster (could be parent or sub)
        Map< String, Object > clusterObj = parentClusters.get( scopeCluster );
        if ( clusterObj == null ) {
            clusterObj = subClusterMap.get( scopeCluster );
        }
        if ( clusterObj == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Cluster not found: " + scopeCluster,
                    "Use get_cluster_map without the cluster parameter to see all cluster names." );
        }

        // Filter pageMetadata to only pages in this cluster
        @SuppressWarnings( "unchecked" )
        final List< String > clusterPageNames = ( List< String > ) clusterObj.get( "pages" );
        final Map< String, Map< String, Object > > filteredMetadata = new LinkedHashMap<>();
        for ( final String pageName : clusterPageNames ) {
            final Map< String, Object > meta = pageMetadataMap.get( pageName );
            if ( meta != null ) {
                filteredMetadata.put( pageName, meta );
            }
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "totalPages", totalPages );
        result.put( "clusters", List.of( clusterObj ) );
        result.put( "pageMetadata", filteredMetadata );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}

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
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.MarkdownLinkScanner;
import com.wikantik.references.ReferenceManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only MCP tool that runs wiki-wide audit checks spanning across clusters:
 * orphaned pages, cross-cluster linking gaps, duplicate summaries,
 * Main page completeness, and global broken links.
 */
public class AuditCrossClusterTool implements McpTool {

    public static final String TOOL_NAME = "audit_cross_cluster";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;
    private final SystemPageRegistry systemPageRegistry;

    public AuditCrossClusterTool( final PageManager pageManager,
                                    final ReferenceManager referenceManager,
                                    final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > summaryItem = new LinkedHashMap<>();
        summaryItem.put( "type", "object" );
        summaryItem.put( "properties", Map.of(
                "page", Map.of( "type", "string" ),
                "summary", Map.of( "type", "string" ) ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "clusters", Map.of( "type", "array", "description",
                "Optional list of cluster names to scope the check. If omitted, checks entire wiki.",
                "items", Map.of( "type", "string" ) ) );
        properties.put( "allSummaries", Map.of( "type", "array", "description",
                "Summaries from audit_cluster calls for duplicate detection",
                "items", summaryItem ) );
        properties.put( "perClusterBrokenLinks", Map.of( "type", "array", "description",
                "Broken links already reported by audit_cluster calls (excluded from global results)",
                "items", Map.of( "type", "string" ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Run wiki-wide audit checks across clusters: " +
                        "orphaned pages, cross-cluster linking gaps, duplicate summaries, " +
                        "Main page completeness, and global broken links. " +
                        "Accepts summaries and broken links from prior audit_cluster calls " +
                        "to avoid redundant work." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< String > scopeClusters = ( List< String > ) arguments.get( "clusters" );
        final List< Map< String, Object > > allSummaries =
                ( List< Map< String, Object > > ) arguments.get( "allSummaries" );
        final List< String > perClusterBrokenLinks =
                ( List< String > ) arguments.get( "perClusterBrokenLinks" );

        // Build cluster→pages and cluster→tags maps
        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Failed to list pages: " + e.getMessage() );
        }

        final Map< String, List< String > > clusterPages = new LinkedHashMap<>();
        final Map< String, Set< String > > clusterTags = new LinkedHashMap<>();
        final Map< String, String > clusterHubs = new LinkedHashMap<>();
        final Map< String, Map< String, Object > > allMetadata = new LinkedHashMap<>();
        final Map< String, String > allBodies = new LinkedHashMap<>();

        for ( final Page page : allPages ) {
            final String pageName = page.getName();
            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                continue;
            }

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > metadata = parsed.metadata();
            allMetadata.put( pageName, metadata );
            allBodies.put( pageName, parsed.body() );

            final Object clusterVal = metadata.get( "cluster" );
            if ( clusterVal instanceof String && !( ( String ) clusterVal ).isBlank() ) {
                final String cluster = ( String ) clusterVal;
                clusterPages.computeIfAbsent( cluster, k -> new ArrayList<>() ).add( pageName );

                if ( "hub".equals( metadata.get( "type" ) ) ) {
                    clusterHubs.put( cluster, pageName );
                }

                // Collect tags
                final Object tagsVal = metadata.get( "tags" );
                if ( tagsVal instanceof List ) {
                    for ( final Object tag : ( List< ? > ) tagsVal ) {
                        if ( tag instanceof String ) {
                            clusterTags.computeIfAbsent( cluster, k -> new HashSet<>() )
                                    .add( ( String ) tag );
                        }
                    }
                }
            }
        }

        // Apply scope filter if provided
        final Set< String > activeClusters;
        if ( scopeClusters != null && !scopeClusters.isEmpty() ) {
            activeClusters = new HashSet<>( scopeClusters );
        } else {
            activeClusters = clusterPages.keySet();
        }

        final Map< String, Object > result = new LinkedHashMap<>();

        // 1. Orphaned pages
        result.put( "orphanedPages", findOrphanedPages() );

        // 2. Cross-cluster gaps
        result.put( "crossClusterGaps", findCrossClusterGaps(
                activeClusters, clusterTags, clusterPages, allMetadata, allBodies ) );

        // 3. Duplicate summaries
        result.put( "duplicateSummaries", findDuplicateSummaries( allSummaries, allMetadata ) );

        // 4. Main page gaps
        result.put( "mainPageGaps", findMainPageGaps( clusterHubs ) );

        // 5. Global broken links
        result.put( "globalBrokenLinks", findGlobalBrokenLinks(
                perClusterBrokenLinks != null ? new HashSet<>( perClusterBrokenLinks ) : Set.of(), allPages ) );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private List< String > findOrphanedPages() {
        final Collection< String > unreferenced = referenceManager.findUnreferenced();
        return unreferenced.stream()
                .filter( name -> !"Main".equals( name ) )
                .filter( name -> systemPageRegistry == null || !systemPageRegistry.isSystemPage( name ) )
                .sorted()
                .collect( Collectors.toList() );
    }

    @SuppressWarnings( "unchecked" )
    private List< Map< String, Object > > findCrossClusterGaps(
            final Set< String > activeClusters,
            final Map< String, Set< String > > clusterTags,
            final Map< String, List< String > > clusterPages,
            final Map< String, Map< String, Object > > allMetadata,
            final Map< String, String > allBodies ) {

        final List< Map< String, Object > > gaps = new ArrayList<>();
        final List< String > clusterList = new ArrayList<>( activeClusters );
        Collections.sort( clusterList );

        for ( int i = 0; i < clusterList.size(); i++ ) {
            for ( int j = i + 1; j < clusterList.size(); j++ ) {
                final String clusterA = clusterList.get( i );
                final String clusterB = clusterList.get( j );

                final Set< String > tagsA = clusterTags.getOrDefault( clusterA, Set.of() );
                final Set< String > tagsB = clusterTags.getOrDefault( clusterB, Set.of() );

                final Set< String > shared = new TreeSet<>( tagsA );
                shared.retainAll( tagsB );

                if ( shared.isEmpty() ) {
                    continue;
                }

                // Check if any cross-references exist
                final boolean hasCrossRef = hasCrossReference(
                        clusterPages.getOrDefault( clusterA, List.of() ),
                        clusterPages.getOrDefault( clusterB, List.of() ),
                        allMetadata, allBodies );

                if ( !hasCrossRef ) {
                    final Map< String, Object > gap = new LinkedHashMap<>();
                    gap.put( "clusterA", clusterA );
                    gap.put( "clusterB", clusterB );
                    gap.put( "sharedTags", new ArrayList<>( shared ) );
                    gap.put( "recommendation", "Add cross-cluster links in hub See Also sections" );
                    gaps.add( gap );
                }
            }
        }
        return gaps;
    }

    @SuppressWarnings( "unchecked" )
    private boolean hasCrossReference( final List< String > pagesA, final List< String > pagesB,
                                         final Map< String, Map< String, Object > > allMetadata,
                                         final Map< String, String > allBodies ) {
        final Set< String > pageSetB = new HashSet<>( pagesB );
        final Set< String > pageSetA = new HashSet<>( pagesA );

        // Check body links from A → B
        for ( final String pageA : pagesA ) {
            final String body = allBodies.get( pageA );
            if ( body != null ) {
                for ( final String link : MarkdownLinkScanner.findLocalLinks( body ) ) {
                    if ( pageSetB.contains( link ) ) {
                        return true;
                    }
                }
            }
            // Check related metadata
            final Map< String, Object > meta = allMetadata.get( pageA );
            if ( meta != null ) {
                final Object related = meta.get( "related" );
                if ( related instanceof List ) {
                    for ( final Object rel : ( List< ? > ) related ) {
                        if ( rel instanceof String && pageSetB.contains( rel ) ) {
                            return true;
                        }
                    }
                }
            }
        }

        // Check body links from B → A
        for ( final String pageB : pagesB ) {
            final String body = allBodies.get( pageB );
            if ( body != null ) {
                for ( final String link : MarkdownLinkScanner.findLocalLinks( body ) ) {
                    if ( pageSetA.contains( link ) ) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @SuppressWarnings( "unchecked" )
    private List< Map< String, Object > > findDuplicateSummaries(
            final List< Map< String, Object > > allSummaries,
            final Map< String, Map< String, Object > > allMetadata ) {

        // Collect all summaries: from allSummaries parameter + from allMetadata
        final Map< String, String > pageSummaries = new LinkedHashMap<>();

        if ( allSummaries != null ) {
            for ( final Map< String, Object > entry : allSummaries ) {
                final String page = ( String ) entry.get( "page" );
                final String summary = ( String ) entry.get( "summary" );
                if ( page != null && summary != null ) {
                    pageSummaries.put( page, summary );
                }
            }
        }

        // Add summaries from allMetadata (only if not already present)
        for ( final Map.Entry< String, Map< String, Object > > entry : allMetadata.entrySet() ) {
            if ( !pageSummaries.containsKey( entry.getKey() ) ) {
                final Object summary = entry.getValue().get( "summary" );
                if ( summary instanceof String ) {
                    pageSummaries.put( entry.getKey(), ( String ) summary );
                }
            }
        }

        // Find exact duplicates and near-duplicates
        final List< Map< String, Object > > duplicates = new ArrayList<>();
        final List< String > pages = new ArrayList<>( pageSummaries.keySet() );

        for ( int i = 0; i < pages.size(); i++ ) {
            for ( int j = i + 1; j < pages.size(); j++ ) {
                final String summaryA = pageSummaries.get( pages.get( i ) );
                final String summaryB = pageSummaries.get( pages.get( j ) );

                if ( summaryA.equals( summaryB ) || normalizedSimilarity( summaryA, summaryB ) > 0.9 ) {
                    final Map< String, Object > dup = new LinkedHashMap<>();
                    dup.put( "pageA", pages.get( i ) );
                    dup.put( "pageB", pages.get( j ) );
                    dup.put( "summary", summaryA );
                    duplicates.add( dup );
                }
            }
        }

        return duplicates;
    }

    /**
     * Simple normalized similarity: ratio of shared words to total unique words.
     */
    private static double normalizedSimilarity( final String a, final String b ) {
        final Set< String > wordsA = new HashSet<>( Arrays.asList( a.toLowerCase().split( "\\s+" ) ) );
        final Set< String > wordsB = new HashSet<>( Arrays.asList( b.toLowerCase().split( "\\s+" ) ) );
        final Set< String > union = new HashSet<>( wordsA );
        union.addAll( wordsB );
        if ( union.isEmpty() ) {
            return 1.0;
        }
        final Set< String > intersection = new HashSet<>( wordsA );
        intersection.retainAll( wordsB );
        return ( double ) intersection.size() / union.size();
    }

    private List< String > findMainPageGaps( final Map< String, String > clusterHubs ) {
        // Read Main page body and check for links to each hub
        final Page mainPage = pageManager.getPage( "Main" );
        if ( mainPage == null ) {
            return new ArrayList<>( clusterHubs.values() );
        }

        final String rawText = pageManager.getPureText( "Main", PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( rawText );
        final Set< String > mainLinks = MarkdownLinkScanner.findLocalLinks( parsed.body() );

        final List< String > gaps = new ArrayList<>();
        for ( final Map.Entry< String, String > entry : clusterHubs.entrySet() ) {
            if ( !mainLinks.contains( entry.getValue() ) ) {
                gaps.add( entry.getValue() );
            }
        }
        Collections.sort( gaps );
        return gaps;
    }

    private List< Map< String, Object > > findGlobalBrokenLinks(
            final Set< String > alreadyReported,
            final Collection< Page > allPages ) {

        final Collection< String > uncreated = referenceManager.findUncreated();
        final List< Map< String, Object > > broken = new ArrayList<>();

        // Collect all page names for suggestion matching
        final Set< String > allPageNames = new HashSet<>();
        for ( final Page page : allPages ) {
            allPageNames.add( page.getName() );
        }

        for ( final String target : uncreated ) {
            if ( alreadyReported.contains( target ) ) {
                continue;
            }

            final Set< String > referrers = referenceManager.findReferrers( target );
            for ( final String source : referrers ) {
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "sourcePage", source );
                entry.put( "targetPage", target );

                // Find closest page name
                final String suggestion = findClosestPage( target, allPageNames );
                if ( suggestion != null ) {
                    entry.put( "suggestion", suggestion );
                }

                broken.add( entry );
            }
        }

        return broken;
    }

    private static String findClosestPage( final String target, final Set< String > allPageNames ) {
        final String targetLower = target.toLowerCase();
        String best = null;
        int bestDist = Integer.MAX_VALUE;

        for ( final String name : allPageNames ) {
            if ( name.toLowerCase().equals( targetLower ) ) {
                return name; // Case-insensitive exact match
            }
            final int dist = levenshtein( targetLower, name.toLowerCase() );
            if ( dist < bestDist && dist <= 2 ) {
                bestDist = dist;
                best = name;
            }
        }
        return best;
    }

    private static int levenshtein( final String a, final String b ) {
        final int m = a.length();
        final int n = b.length();
        final int[] prev = new int[ n + 1 ];
        final int[] curr = new int[ n + 1 ];

        for ( int j = 0; j <= n; j++ ) {
            prev[ j ] = j;
        }
        for ( int i = 1; i <= m; i++ ) {
            curr[ 0 ] = i;
            for ( int j = 1; j <= n; j++ ) {
                final int cost = a.charAt( i - 1 ) == b.charAt( j - 1 ) ? 0 : 1;
                curr[ j ] = Math.min( Math.min( curr[ j - 1 ] + 1, prev[ j ] + 1 ), prev[ j - 1 ] + cost );
            }
            System.arraycopy( curr, 0, prev, 0, n + 1 );
        }
        return prev[ n ];
    }
}

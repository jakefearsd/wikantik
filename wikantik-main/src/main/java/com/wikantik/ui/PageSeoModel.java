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
package com.wikantik.ui;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.ontology.NodeTypeMapping;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every derived, page-level SEO/structured-data value that
 * {@link SemanticHeadRenderer#renderHead} needs before it starts emitting HTML: the fallback
 * chains (summary &gt; description &gt; generic; frontmatter image &gt; default), the
 * title/app-name dedup, and the ontology-sourced schema.org type.
 *
 * <p>{@link #from} is a pure function of the same five inputs {@code renderHead} takes, copied
 * verbatim from its former inline derivation block — same order, same null-guards — so emission
 * built on top of this model is behaviorally identical to before the split.
 *
 * @param safePageName         {@code pageName}, or {@code ""} if {@code null}
 * @param safeAppName          {@code appName}, or {@code ""} if {@code null}
 * @param safeBaseUrl          {@code baseUrl} with any trailing slash stripped, or {@code ""} if {@code null}
 * @param canonical            {@code safeBaseUrl + "/wiki/" + safePageName}
 * @param documentTitle        {@code effectiveTitle} with the app name appended (deduped)
 * @param effectiveTitle       frontmatter {@code title} when present, else {@code safePageName}
 * @param effectiveDescription frontmatter {@code summary} &gt; {@code description} &gt; generic fallback
 * @param effectiveKeywords    comma-joined {@code tags}
 * @param pageDate             frontmatter {@code date}, formatted or stringified; {@code ""} if absent
 * @param cluster              frontmatter {@code cluster}, or {@code ""} if absent
 * @param isHub                {@code true} when frontmatter {@code type} is {@code "hub"}
 * @param related              frontmatter {@code related} list
 * @param tags                 frontmatter {@code tags} list
 * @param schemaType           schema.org {@code @type}, re-sourced from {@link NodeTypeMapping#schemaOrgType}
 * @param canonicalId          frontmatter {@code canonical_id}, or {@code ""} if absent
 * @param imageUrl             frontmatter {@code image} (absolute, or base-url-prefixed) or the default OG image
 * @param hasCustomImage       {@code true} when frontmatter supplied a non-blank {@code image}
 * @param modified             the page's last-modified timestamp, or {@code null}
 * @param metadata             the raw frontmatter metadata map
 */
record PageSeoModel(
        String safePageName,
        String safeAppName,
        String safeBaseUrl,
        String canonical,
        String documentTitle,
        String effectiveTitle,
        String effectiveDescription,
        String effectiveKeywords,
        String pageDate,
        String cluster,
        boolean isHub,
        List< String > related,
        List< String > tags,
        String schemaType,
        String canonicalId,
        String imageUrl,
        boolean hasCustomImage,
        Date modified,
        Map< String, Object > metadata ) {

    /**
     * Derive a {@link PageSeoModel} from the raw page inputs.
     *
     * @param pageName    the wiki page name
     * @param rawPageText the raw page text, possibly with a YAML frontmatter block
     * @param baseUrl     the fully-qualified base URL (no trailing slash)
     * @param appName     the wiki application name
     * @param modified    the page's last-modified timestamp, or {@code null}
     * @return the derived model
     */
    static PageSeoModel from( final String pageName, final String rawPageText, final String baseUrl,
                               final String appName, final Date modified ) {
        final ParsedPage parsed = FrontmatterParser.parse( orEmpty( rawPageText ) );
        final Map< String, Object > meta = parsed.metadata();

        final String safePageName = orEmpty( pageName );
        final String safeAppName = orEmpty( appName );
        final String safeBaseUrl = stripTrailingSlash( orEmpty( baseUrl ) );
        final String canonical = safeBaseUrl + "/wiki/" + safePageName;

        final String fmTitle = strOrEmpty( meta.get( "title" ) );
        // Document title: human-authored frontmatter title when present, else the
        // raw page name. The application name is appended for SERP context unless
        // it is already part of the title (avoids "WikantikOnDocker - Wikantik").
        final String effectiveTitle = resolveEffectiveTitle( fmTitle, safePageName );
        final String documentTitle = titleWithApp( effectiveTitle, safeAppName );

        final String summary = strOrEmpty( meta.get( "summary" ) );
        final String description = strOrEmpty( meta.get( "description" ) );
        final String pageType = strOrEmpty( meta.get( "type" ) );
        final String cluster = strOrEmpty( meta.get( "cluster" ) );
        final String pageDate = dateOrString( meta.get( "date" ) );
        final List< String > tags = stringList( meta.get( "tags" ) );
        final List< String > related = stringList( meta.get( "related" ) );
        final boolean isHub = "hub".equalsIgnoreCase( pageType );
        // schema.org @type re-sourced from the ontology's page-type mapping (Phase 6): the SEO
        // classification and the ontology projection share one source. Upgrade-only.
        final String schemaType = NodeTypeMapping.schemaOrgType( pageType );
        final String canonicalId = strOrEmpty( meta.get( "canonical_id" ) );

        // effective description: summary > description > generic fallback
        final String effectiveDescription = resolveEffectiveDescription( summary, description,
                safePageName, safeAppName );
        final String effectiveKeywords = String.join( ", ", tags );

        // Determine image URL: frontmatter image field > default
        final String fmImage = strOrEmpty( meta.get( "image" ) );
        final boolean hasCustomImage = !fmImage.isBlank();
        final String imageUrl = resolveImageUrl( fmImage, hasCustomImage, safeBaseUrl );

        return new PageSeoModel( safePageName, safeAppName, safeBaseUrl, canonical, documentTitle,
                effectiveTitle, effectiveDescription, effectiveKeywords, pageDate, cluster, isHub,
                related, tags, schemaType, canonicalId, imageUrl, hasCustomImage, modified, meta );
    }

    // -------- derivation sub-steps (extracted to keep `from` itself trivially simple) --------

    private static String orEmpty( final String s ) {
        return s == null ? "" : s;
    }

    private static String resolveEffectiveTitle( final String fmTitle, final String safePageName ) {
        return !fmTitle.isBlank() ? fmTitle : safePageName;
    }

    private static String resolveEffectiveDescription( final String summary, final String description,
                                                         final String safePageName, final String safeAppName ) {
        if ( !summary.isBlank() ) {
            return summary;
        }
        if ( !description.isBlank() ) {
            return description;
        }
        return safePageName + " - " + safeAppName + " wiki page.";
    }

    private static String resolveImageUrl( final String fmImage, final boolean hasCustomImage,
                                            final String safeBaseUrl ) {
        if ( !hasCustomImage ) {
            return safeBaseUrl + "/og-default.png";
        }
        if ( fmImage.startsWith( "http://" ) || fmImage.startsWith( "https://" ) ) {
            return fmImage;
        }
        return safeBaseUrl + "/" + ( fmImage.startsWith( "/" ) ? fmImage.substring( 1 ) : fmImage );
    }

    // -------- type coercion helpers (derivation-only; moved from SemanticHeadRenderer) --------

    private static String strOrEmpty( final Object value ) {
        if ( value == null ) {
            return "";
        }
        if ( value instanceof Date date ) {
            return new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT ).format( date );
        }
        return value.toString();
    }

    private static String dateOrString( final Object value ) {
        if ( value == null ) {
            return "";
        }
        if ( value instanceof Date date ) {
            return new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT ).format( date );
        }
        return value.toString();
    }

    private static List< String > stringList( final Object value ) {
        if ( value == null ) {
            return List.of();
        }
        if ( value instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object e : list ) {
                if ( e != null ) {
                    out.add( e.toString().trim() );
                }
            }
            return out;
        }
        // Comma-separated scalar (e.g. "a, b, c")
        final String csv = value.toString();
        if ( csv.isBlank() ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>();
        for ( final String part : csv.split( "," ) ) {
            final String trimmed = part.trim();
            if ( !trimmed.isEmpty() ) {
                out.add( trimmed );
            }
        }
        return out;
    }

    private static String stripTrailingSlash( final String s ) {
        if ( s == null || s.isEmpty() ) {
            return s;
        }
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }

    /**
     * Compose the document title, appending the application name for SERP
     * context but only when it is not already present in the title (so
     * "Wikantik on Docker" does not become "Wikantik on Docker - Wikantik").
     *
     * @param title   the page-level title (frontmatter title or page name)
     * @param appName the wiki application name
     * @return the composed document title
     */
    private static String titleWithApp( final String title, final String appName ) {
        final String t = title == null ? "" : title.trim();
        final String app = appName == null ? "" : appName.trim();
        if ( app.isEmpty() ) {
            return t;
        }
        if ( t.isEmpty() ) {
            return app;
        }
        if ( t.toLowerCase( Locale.ROOT ).contains( app.toLowerCase( Locale.ROOT ) ) ) {
            return t;
        }
        return t + " - " + app;
    }
}

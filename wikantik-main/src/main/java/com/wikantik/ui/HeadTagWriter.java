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

/**
 * Writes the non-JSON-LD {@code <head>} tags for a page: title, canonical
 * link, meta description/keywords/robots, Open Graph, Twitter Card, and Atom
 * feed autodiscovery. Split out of {@link SemanticHeadRenderer#renderHead} —
 * the emission logic (append order and string content) is copied verbatim,
 * so output is byte-identical to before the split.
 */
final class HeadTagWriter {

    private static final String NL = "\n";

    private HeadTagWriter() {
    }

    /**
     * Append the head-tag block for {@code model} to {@code sb}, in the same
     * order the tags must appear before the JSON-LD scripts that follow.
     *
     * @param sb    the buffer being built by {@link SemanticHeadRenderer#renderHead}
     * @param model the derived page SEO model
     */
    static void write( final StringBuilder sb, final PageSeoModel model ) {
        sb.append( "<title>" ).append( SemanticHeadRenderer.escAttr( model.documentTitle() ) ).append( "</title>" ).append( NL );

        sb.append( "<link rel=\"canonical\" href=\"" ).append( SemanticHeadRenderer.escAttr( model.canonical() ) ).append( "\" />" ).append( NL );

        sb.append( "<meta name=\"description\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.effectiveDescription() ) ).append( "\" />" ).append( NL );

        if ( !model.effectiveKeywords().isBlank() ) {
            sb.append( "<meta name=\"keywords\" content=\"" )
              .append( SemanticHeadRenderer.escAttr( model.effectiveKeywords() ) ).append( "\" />" ).append( NL );
        }

        // meta robots — lift Google snippet/preview caps (does NOT noindex)
        sb.append( "<meta name=\"robots\" content=\"max-image-preview:large, max-snippet:-1, max-video-preview:-1\" />" ).append( NL );

        writeOpenGraph( sb, model );
        writeTwitterCard( sb, model );
        writeAtomFeedLinks( sb, model );
    }

    private static void writeOpenGraph( final StringBuilder sb, final PageSeoModel model ) {
        sb.append( "<meta property=\"og:title\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.documentTitle() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:type\" content=\"article\" />" ).append( NL );
        sb.append( "<meta property=\"og:url\" content=\"" ).append( SemanticHeadRenderer.escAttr( model.canonical() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:description\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.effectiveDescription() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:site_name\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.safeAppName() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:image\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.imageUrl() ) ).append( "\" />" ).append( NL );
        if ( !model.hasCustomImage() ) {
            sb.append( "<meta property=\"og:image:width\" content=\"1200\" />" ).append( NL );
            sb.append( "<meta property=\"og:image:height\" content=\"630\" />" ).append( NL );
        }

        // article:tag per tag
        for ( final String tag : model.tags() ) {
            sb.append( "<meta property=\"article:tag\" content=\"" )
              .append( SemanticHeadRenderer.escAttr( tag ) ).append( "\" />" ).append( NL );
        }
    }

    private static void writeTwitterCard( final StringBuilder sb, final PageSeoModel model ) {
        sb.append( "<meta name=\"twitter:card\" content=\"summary_large_image\" />" ).append( NL );
        sb.append( "<meta name=\"twitter:title\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.documentTitle() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta name=\"twitter:description\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.effectiveDescription() ) ).append( "\" />" ).append( NL );
        sb.append( "<meta name=\"twitter:image\" content=\"" )
          .append( SemanticHeadRenderer.escAttr( model.imageUrl() ) ).append( "\" />" ).append( NL );
    }

    private static void writeAtomFeedLinks( final StringBuilder sb, final PageSeoModel model ) {
        // Atom feed autodiscovery — global and (optionally) cluster-filtered
        sb.append( "<link rel=\"alternate\" type=\"application/atom+xml\" title=\"" )
          .append( SemanticHeadRenderer.escAttr( model.safeAppName() + " - Recent Articles" ) ).append( "\" href=\"" )
          .append( SemanticHeadRenderer.escAttr( model.safeBaseUrl() + "/feed.xml" ) ).append( "\" />" ).append( NL );
        if ( !model.cluster().isBlank() ) {
            sb.append( "<link rel=\"alternate\" type=\"application/atom+xml\" title=\"" )
              .append( SemanticHeadRenderer.escAttr( model.safeAppName() + " - " + model.cluster() + " Articles" ) ).append( "\" href=\"" )
              .append( SemanticHeadRenderer.escAttr( model.safeBaseUrl() + "/feed.xml?cluster=" + model.cluster() ) ).append( "\" />" ).append( NL );
        }
    }
}

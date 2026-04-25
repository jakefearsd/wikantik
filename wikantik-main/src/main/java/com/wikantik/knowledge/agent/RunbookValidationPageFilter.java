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
package com.wikantik.knowledge.agent;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;
import java.util.function.Predicate;

/**
 * Phase 3 enforcement filter for runbook pages. Runs in {@code preSave};
 * when the page declares {@code type: runbook}, the {@code runbook:} block
 * must satisfy {@link FrontmatterRunbookValidator}'s schema rules. Failures
 * abort the save with {@link FilterException}.
 *
 * <p>Non-runbook pages and saves with the property
 * {@link #PROP_ENFORCEMENT_ENABLED} set to {@code false} pass through
 * untouched. The filter never rewrites content — it is read-only on the
 * way through.</p>
 */
public class RunbookValidationPageFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( RunbookValidationPageFilter.class );

    /** Master flag; default {@code true}. */
    public static final String PROP_ENFORCEMENT_ENABLED = "wikantik.runbook.enforcement.enabled";

    private final StructuralIndexService structuralIndex;
    private final PageManager pageManager;
    private final boolean enabled;

    public RunbookValidationPageFilter( final StructuralIndexService structuralIndex,
                                         final PageManager pageManager,
                                         final Properties props ) {
        this.structuralIndex = structuralIndex;
        this.pageManager = pageManager;
        this.enabled = Boolean.parseBoolean(
                props.getProperty( PROP_ENFORCEMENT_ENABLED, "true" ) );
        LOG.info( "RunbookValidationPageFilter: enforcement {}",
                  enabled ? "enabled" : "disabled" );
    }

    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        if ( !enabled ) {
            return content;
        }
        final ParsedPage parsed = FrontmatterParser.parse( content );
        final Object typeRaw = parsed.metadata().get( "type" );
        if ( typeRaw == null || !"runbook".equalsIgnoreCase( typeRaw.toString().trim() ) ) {
            return content;
        }

        final Predicate< String > canonicalIdResolves = id ->
                structuralIndex != null && structuralIndex.getByCanonicalId( id ).isPresent();
        final Predicate< String > pageTitleResolves = name -> {
            if ( pageManager == null ) return false;
            try {
                return pageManager.pageExists( name );
            } catch ( final ProviderException e ) {
                LOG.warn( "RunbookValidationPageFilter: pageExists({}) threw — treating as unresolved: {}",
                        name, e.getMessage() );
                return false;
            }
        };

        final FrontmatterRunbookValidator.Result result = FrontmatterRunbookValidator.validate(
                parsed.metadata(), canonicalIdResolves, pageTitleResolves );
        if ( result.hasIssues() ) {
            final String pageName = context != null && context.getPage() != null
                    ? context.getPage().getName() : "<unknown>";
            final StringBuilder msg = new StringBuilder();
            msg.append( "Runbook page '" ).append( pageName ).append( "' has invalid frontmatter:" );
            for ( final var issue : result.issues() ) {
                msg.append( ' ' ).append( '[' ).append( issue.kind() )
                   .append( ' ' ).append( issue.detail() ).append( ']' );
            }
            throw new FilterException( msg.toString() );
        }
        return content;
    }
}

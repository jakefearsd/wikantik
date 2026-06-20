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
package com.wikantik.frontmatter.schema;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Save-time guard that enforces the server-authoritative frontmatter schema on every save path.
 * Replaces the old {@code FrontmatterValidationPageFilter}: it folds in the strict-YAML parse (a
 * {@code __yaml__} violation on failure) and adds {@link SchemaDrivenFrontmatterValidator} field
 * checks. {@code ERROR} violations throw {@link FrontmatterValidationException} (the page is not
 * written); {@code WARNING} violations are stashed on {@link FrontmatterWarningSink} for the REST layer
 * to return on the successful response.
 *
 * <p>Runs after {@code StructuralSpinePageFilter} (higher priority number runs first), so
 * {@code canonical_id} is present. Gated by {@link #PROP_ENFORCEMENT_ENABLED} (default {@code true}).</p>
 */
public class SchemaValidationPageFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( SchemaValidationPageFilter.class );

    /** Master flag; default {@code true}. Disable only while migrating an existing dirty corpus. */
    public static final String PROP_ENFORCEMENT_ENABLED = "wikantik.frontmatter.enforcement.enabled";
    /** {@code warning} (default) or {@code error}: severity for non-canonical curated-open enum values. */
    public static final String PROP_NONCANONICAL_SEVERITY = "wikantik.frontmatter.enum.nonCanonical.severity";
    /** Comma-separated trusted authors for the {@code verified_by} advisory check. */
    public static final String PROP_TRUSTED_AUTHORS = "wikantik.frontmatter.trustedAuthors";

    private final SchemaDrivenFrontmatterValidator validator;
    private final ValidationCtx ctx;
    private final boolean enabled;

    /** Test-friendly constructor with explicit collaborators. */
    public SchemaValidationPageFilter( final SchemaDrivenFrontmatterValidator validator,
                                       final ValidationCtx ctx, final boolean enabled ) {
        this.validator = validator;
        this.ctx = ctx;
        this.enabled = enabled;
        LOG.info( "SchemaValidationPageFilter: enforcement {}", enabled ? "enabled" : "disabled" );
    }

    /** Production constructor: builds the default schema + an engine-backed validation context. */
    public SchemaValidationPageFilter( final Properties props, final PageManager pageManager ) {
        this( new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
              engineBackedCtx( props, pageManager ),
              Boolean.parseBoolean( props.getProperty( PROP_ENFORCEMENT_ENABLED, "true" ) ) );
    }

    /** Engine-backed context shared by the save filter and the drift sweep. */
    public static ValidationCtx engineBackedCtx( final Properties props, final PageManager pageManager ) {
        final Severity nonCanonical = "error".equalsIgnoreCase(
                props.getProperty( PROP_NONCANONICAL_SEVERITY, "warning" ) )
                ? Severity.ERROR : Severity.WARNING;
        final Set< String > trusted = parseTrusted( props.getProperty( PROP_TRUSTED_AUTHORS ) );
        final Predicate< String > pageResolves = name -> pageManager.wikiPageExists( name );
        final Predicate< String > isTrustedAuthor = trusted.isEmpty() ? a -> true : trusted::contains;
        return new ValidationCtx( pageResolves, isTrustedAuthor, nonCanonical );
    }

    private static Set< String > parseTrusted( final String csv ) {
        if ( csv == null || csv.isBlank() ) {
            return Set.of();
        }
        return Arrays.stream( csv.split( "," ) ).map( String::trim )
                .filter( s -> !s.isEmpty() ).collect( Collectors.toUnmodifiableSet() );
    }

    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        if ( !enabled || content == null || content.isEmpty() ) {
            return content;
        }
        if ( !content.startsWith( "---\n" ) && !content.startsWith( "---\r\n" ) ) {
            return content;
        }

        final ParsedPage parsed;
        try {
            parsed = FrontmatterParser.parseStrict( content );
        } catch ( final FrontmatterParseException e ) {
            final String loc = e.line() > 0
                    ? " (line " + e.line() + ( e.column() > 0 ? ", column " + e.column() : "" ) + ")"
                    : "";
            throw new FrontmatterValidationException( List.of( FieldViolation.of(
                    "__yaml__", Severity.ERROR, "yaml.parse",
                    "Malformed YAML frontmatter: " + e.getMessage() + loc
                            + ". Wrap values containing ':' or other YAML special characters in "
                            + "double quotes, e.g. title: \"Foo: Bar\"." ) ), e );
        }

        final List< FieldViolation > all = validator.validate( parsed.metadata(), ctx );
        final List< FieldViolation > errors =
                all.stream().filter( v -> v.severity() == Severity.ERROR ).toList();
        if ( !errors.isEmpty() ) {
            throw new FrontmatterValidationException( errors );
        }
        // Key the stash by the page being saved so a NESTED save (e.g. a regenerated index/hub page)
        // running this same filter cannot clobber this page's warnings — that cross-page leak is what
        // made update_page surface a foreign "summary is N chars" warning under concurrent/nested saves.
        final String pageName = context != null && context.getPage() != null
                ? context.getPage().getName() : null;
        FrontmatterWarningSink.put( pageName,
                all.stream().filter( v -> v.severity() == Severity.WARNING ).toList() );
        return content;
    }
}

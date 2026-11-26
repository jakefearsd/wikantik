package com.wikantik.agentreports;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the agent-supplied arguments of a filing. Nothing is truncated: anything invalid or over a
 * limit is refused with a reason that names the field. {@code client}, {@code session_hash} and
 * {@code filed_by} are never read — the server fills them.
 */
public final class AgentReportValidator {

    static final Set< String > KINDS = Set.of( "operations", "content", "build", "research", "other" );
    static final Set< String > OUTCOMES = Set.of( "completed", "partial", "abandoned" );
    static final Set< String > SOURCES = Set.of( "wiki", "blog", "repo", "web" );
    static final Set< String > HELPED = Set.of( "yes", "partly", "no" );

    static final int MAX_TASK = 300;
    static final int MAX_CONTEXT_MISSING = 1000;
    static final int MAX_WHAT = 500;
    static final int MAX_NOTES = 2000;
    static final int MAX_WORKSPACE = 100;
    static final int MAX_LIST = 50;
    /** Per-entry limits the spec leaves open: short identifiers and a reference. */
    static final int MAX_NAME = 100;
    static final int MAX_REF = 500;

    private AgentReportValidator() { }

    public static Validation validate( final Map< String, Object > args ) {
        final List< String > errors = new ArrayList<>();
        final String kind = enumField( args, "kind_of_work", KINDS, true, errors );
        final String outcome = enumField( args, "outcome", OUTCOMES, true, errors );
        final String task = text( args, "task", MAX_TASK, true, errors );
        final String workspace = text( args, "workspace", MAX_WORKSPACE, false, errors );
        final String contextMissing = text( args, "context_missing", MAX_CONTEXT_MISSING, false, errors );
        final String notes = text( args, "notes", MAX_NOTES, false, errors );
        final List< String > products = strings( args, "products", errors );
        final List< String > skills = strings( args, "skills", errors );
        final Instant start = instant( args, "window_start", errors );
        final Instant end = instant( args, "window_end", errors );
        if ( start != null && end != null && end.isBefore( start ) ) {
            errors.add( "window_end: must not be before window_start" );
        }
        final List< ContextUse > contexts = contexts( args, errors );
        final List< Friction > friction = frictions( args, errors );
        if ( !errors.isEmpty() ) {
            return Validation.refused( errors );
        }
        return Validation.ok( new AgentReport( null, null, null, null, null, workspace, kind, products, skills,
            start, end, task, outcome, contexts, contextMissing, friction, notes ) );
    }

    private static int chars( final String s ) {
        return s.codePointCount( 0, s.length() );
    }

    /** A string field: absent/blank is an error only when required; wrong type or too long is always one. */
    private static String text( final Map< String, Object > m, final String field, final int max,
                                final boolean required, final List< String > errors ) {
        final Object v = m.get( field );
        if ( v == null || v instanceof String s && s.isBlank() ) {
            if ( required ) {
                errors.add( field + ": required" );
            }
            return null;
        }
        if ( !( v instanceof String s ) ) {
            errors.add( field + ": must be a string" );
            return null;
        }
        if ( s.indexOf( '\u0000' ) >= 0 ) {
            errors.add( field + ": must not contain NUL (U+0000) characters" );
            return null;
        }
        if ( chars( s ) > max ) {
            errors.add( field + ": at most " + max + " characters (got " + chars( s ) + ")" );
            return null;
        }
        return s;
    }

    private static String enumField( final Map< String, Object > m, final String field, final Set< String > allowed,
                                     final boolean required, final List< String > errors ) {
        final String s = text( m, field, MAX_NAME, required, errors );
        if ( s != null && !allowed.contains( s ) ) {
            errors.add( field + ": must be one of " + allowed.stream().sorted().toList() + " (got '" + s + "')" );
            return null;
        }
        return s;
    }

    private static List< ? > list( final Map< String, Object > m, final String field, final List< String > errors ) {
        final Object v = m.get( field );
        if ( v == null ) {
            return List.of();
        }
        if ( !( v instanceof List< ? > l ) ) {
            errors.add( field + ": must be a list" );
            return List.of();
        }
        if ( l.size() > MAX_LIST ) {
            errors.add( field + ": at most " + MAX_LIST + " entries (got " + l.size() + ")" );
            return List.of();
        }
        return l;
    }

    private static List< String > strings( final Map< String, Object > m, final String field,
                                           final List< String > errors ) {
        final List< String > out = new ArrayList<>();
        final List< ? > l = list( m, field, errors );
        for ( int i = 0; i < l.size(); i++ ) {
            final Object v = l.get( i );
            if ( v instanceof String s && s.indexOf( '\u0000' ) >= 0 ) {
                errors.add( field + "[" + i + "]: must not contain NUL (U+0000) characters" );
            } else if ( v instanceof String s && !s.isBlank() && chars( s ) <= MAX_NAME ) {
                out.add( s );
            } else {
                errors.add( field + "[" + i + "]: must be a non-empty string of at most " + MAX_NAME + " characters" );
            }
        }
        return out;
    }

    private static Instant instant( final Map< String, Object > m, final String field, final List< String > errors ) {
        final Object v = m.get( field );
        if ( v == null ) {
            return null;
        }
        if ( v instanceof Instant i ) {
            return i;
        }
        if ( v instanceof String s ) {
            try {
                return OffsetDateTime.parse( s ).toInstant();
            } catch ( final DateTimeParseException e ) {
                errors.add( field + ": not an ISO-8601 timestamp with offset (got '" + s + "')" );
                return null;
            }
        }
        errors.add( field + ": must be an ISO-8601 timestamp string" );
        return null;
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > entry( final List< ? > l, final int i, final String field,
                                                final List< String > errors ) {
        if ( l.get( i ) instanceof Map< ?, ? > e ) {
            return ( Map< String, Object > ) e;
        }
        errors.add( field + "[" + i + "]: must be an object" );
        return null;
    }

    private static List< ContextUse > contexts( final Map< String, Object > args, final List< String > errors ) {
        final List< ContextUse > out = new ArrayList<>();
        final List< ? > l = list( args, "context_used", errors );
        for ( int i = 0; i < l.size(); i++ ) {
            final Map< String, Object > e = entry( l, i, "context_used", errors );
            if ( e == null ) {
                continue;
            }
            final String p = "context_used[" + i + "].";
            final int before = errors.size();
            final String source = enumField( prefixed( e, "source", p ), p + "source", SOURCES, true, errors );
            final String ref = text( prefixed( e, "ref", p ), p + "ref", MAX_REF, true, errors );
            final String helped = enumField( prefixed( e, "helped", p ), p + "helped", HELPED, true, errors );
            if ( errors.size() == before ) {
                out.add( new ContextUse( source, ref, helped ) );
            }
        }
        return out;
    }

    private static List< Friction > frictions( final Map< String, Object > args, final List< String > errors ) {
        final List< Friction > out = new ArrayList<>();
        final List< ? > l = list( args, "friction", errors );
        for ( int i = 0; i < l.size(); i++ ) {
            final Map< String, Object > e = entry( l, i, "friction", errors );
            if ( e == null ) {
                continue;
            }
            final String p = "friction[" + i + "].";
            final int before = errors.size();
            final String product = text( prefixed( e, "product", p ), p + "product", MAX_NAME, true, errors );
            final String tool = text( prefixed( e, "tool", p ), p + "tool", MAX_NAME, false, errors );
            final String what = text( prefixed( e, "what", p ), p + "what", MAX_WHAT, true, errors );
            final Object rawBlocking = e.get( "blocking" );
            if ( rawBlocking != null && !( rawBlocking instanceof Boolean ) ) {
                errors.add( p + "blocking: must be a boolean" );
            }
            final boolean blocking = Boolean.TRUE.equals( rawBlocking );
            if ( errors.size() == before ) {
                out.add( new Friction( product, tool, what, blocking ) );
            }
        }
        return out;
    }

    /** Re-keys one entry field under its full path so {@link #text} reports the full field name. */
    private static Map< String, Object > prefixed( final Map< String, Object > e, final String key,
                                                   final String prefix ) {
        final Object v = e.get( key );
        return v == null ? Map.of() : Map.of( prefix + key, v );
    }
}

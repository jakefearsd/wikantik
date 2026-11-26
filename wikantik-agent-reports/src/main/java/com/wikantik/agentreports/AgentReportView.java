package com.wikantik.agentreports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain-map rendering of an {@link AgentReport} for JSON surfaces (snake_case keys, instants as ISO strings). */
public final class AgentReportView {

    private AgentReportView() {}

    public static Map< String, Object > toMap( final AgentReport r ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", r.id() );
        m.put( "filed_at", str( r.filedAt() ) );
        m.put( "client", r.client() );
        m.put( "session_hash", r.sessionHash() );
        m.put( "filed_by", r.filedBy() );
        m.put( "workspace", r.workspace() );
        m.put( "kind_of_work", r.kindOfWork() );
        m.put( "products", r.products() );
        m.put( "skills", r.skills() );
        m.put( "window_start", str( r.windowStart() ) );
        m.put( "window_end", str( r.windowEnd() ) );
        m.put( "task", r.task() );
        m.put( "outcome", r.outcome() );
        m.put( "context_used", contextUsed( r.contextUsed() ) );
        m.put( "context_missing", r.contextMissing() );
        m.put( "friction", friction( r.friction() ) );
        m.put( "notes", r.notes() );
        return m;
    }

    public static List< Map< String, Object > > toMaps( final List< AgentReport > reports ) {
        final List< Map< String, Object > > out = new ArrayList<>( reports.size() );
        for ( final AgentReport r : reports ) {
            out.add( toMap( r ) );
        }
        return out;
    }

    private static String str( final Object o ) {
        return o == null ? null : o.toString();
    }

    private static List< Map< String, Object > > contextUsed( final List< ContextUse > uses ) {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final ContextUse u : uses == null ? List.< ContextUse >of() : uses ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "source", u.source() );
            m.put( "ref", u.ref() );
            m.put( "helped", u.helped() );
            out.add( m );
        }
        return out;
    }

    private static List< Map< String, Object > > friction( final List< Friction > items ) {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final Friction f : items == null ? List.< Friction >of() : items ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "product", f.product() );
            m.put( "tool", f.tool() );
            m.put( "what", f.what() );
            m.put( "blocking", f.blocking() );
            out.add( m );
        }
        return out;
    }
}

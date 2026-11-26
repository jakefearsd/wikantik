package com.wikantik.agentreports;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReportValidatorTest {

    private static Map< String, Object > minimal() {
        final Map< String, Object > m = new HashMap<>();
        m.put( "kind_of_work", "build" );
        m.put( "task", "Wire the thing" );
        m.put( "outcome", "completed" );
        return m;
    }

    private static void assertRefusedNaming( final Map< String, Object > args, final String field ) {
        final Validation v = AgentReportValidator.validate( args );
        assertFalse( v.isOk(), "expected refusal for " + field );
        assertTrue( v.reasons().stream().anyMatch( r -> r.contains( field ) ), v.reasons().toString() );
    }

    @Test
    void minimalMapIsOkWithEmptyLists() {
        final Validation v = AgentReportValidator.validate( minimal() );
        assertTrue( v.isOk(), v.reasons().toString() );
        final AgentReport r = v.draft();
        assertEquals( "build", r.kindOfWork() );
        assertEquals( "Wire the thing", r.task() );
        assertEquals( "completed", r.outcome() );
        assertTrue( r.products().isEmpty() );
        assertTrue( r.contextUsed().isEmpty() );
        assertTrue( r.friction().isEmpty() );
    }

    @Test
    void badEnumsAreRefusedNamingTheField() {
        final Map< String, Object > a = minimal();
        a.put( "kind_of_work", "gardening" );
        assertRefusedNaming( a, "kind_of_work" );
        final Map< String, Object > b = minimal();
        b.put( "outcome", "meh" );
        assertRefusedNaming( b, "outcome" );
        final Map< String, Object > c = minimal();
        c.put( "context_used", List.of( Map.of( "source", "tv", "ref", "x", "helped", "yes" ) ) );
        assertRefusedNaming( c, "context_used[0].source" );
        final Map< String, Object > d = minimal();
        d.put( "context_used", List.of( Map.of( "source", "wiki", "ref", "x", "helped", "maybe" ) ) );
        assertRefusedNaming( d, "context_used[0].helped" );
    }

    @Test
    void requiredFieldsMissing() {
        for ( final String f : List.of( "kind_of_work", "task", "outcome" ) ) {
            final Map< String, Object > m = minimal();
            m.remove( f );
            assertRefusedNaming( m, f );
        }
    }

    @Test
    void lengthLimitsCountCharactersNotBytes() {
        final Map< String, Object > m = minimal();
        m.put( "task", "x".repeat( 301 ) );
        assertRefusedNaming( m, "task" );
        m.put( "task", "é".repeat( 300 ) );
        assertTrue( AgentReportValidator.validate( m ).isOk() );

        final Map< String, Object > n = minimal();
        n.put( "context_missing", "x".repeat( 1001 ) );
        assertRefusedNaming( n, "context_missing" );
        final Map< String, Object > o = minimal();
        o.put( "notes", "x".repeat( 2001 ) );
        assertRefusedNaming( o, "notes" );
        final Map< String, Object > w = minimal();
        w.put( "workspace", "x".repeat( 101 ) );
        assertRefusedNaming( w, "workspace" );
        final Map< String, Object > f = minimal();
        f.put( "friction", List.of( Map.of( "product", "wikantik", "what", "x".repeat( 501 ) ) ) );
        assertRefusedNaming( f, "friction[0].what" );
    }

    @Test
    void listsOverFiftyAreRefused() {
        final List< String > fiftyOne = new ArrayList<>();
        for ( int i = 0; i < 51; i++ ) fiftyOne.add( "p" + i );
        final Map< String, Object > m = minimal();
        m.put( "products", fiftyOne );
        assertRefusedNaming( m, "products" );
        final Map< String, Object > s = minimal();
        s.put( "skills", fiftyOne );
        assertRefusedNaming( s, "skills" );
        final List< Object > ctx = new ArrayList<>();
        final List< Object > fr = new ArrayList<>();
        for ( int i = 0; i < 51; i++ ) {
            ctx.add( Map.of( "source", "wiki", "ref", "r" + i, "helped", "yes" ) );
            fr.add( Map.of( "product", "p", "what", "w" ) );
        }
        final Map< String, Object > c = minimal();
        c.put( "context_used", ctx );
        assertRefusedNaming( c, "context_used" );
        final Map< String, Object > g = minimal();
        g.put( "friction", fr );
        assertRefusedNaming( g, "friction" );
        fiftyOne.remove( 0 );
        final Map< String, Object > ok = minimal();
        ok.put( "products", fiftyOne );
        assertTrue( AgentReportValidator.validate( ok ).isOk() );
    }

    @Test
    void serverFilledKeysAreIgnored() {
        final Map< String, Object > m = minimal();
        m.put( "client", "evil" );
        m.put( "filed_by", "root" );
        m.put( "session_hash", "abc" );
        final Validation v = AgentReportValidator.validate( m );
        assertTrue( v.isOk() );
        assertNull( v.draft().client() );
        assertNull( v.draft().filedBy() );
        assertNull( v.draft().sessionHash() );
    }

    @Test
    void fullMapParses() {
        final Map< String, Object > m = minimal();
        m.put( "workspace", "jspwiki" );
        m.put( "products", List.of( "wikantik", "jakemon" ) );
        m.put( "skills", List.of( "wiki-content" ) );
        m.put( "window_start", "2026-11-26T10:00:00Z" );
        m.put( "window_end", "2026-11-26T11:00:00Z" );
        m.put( "context_used", List.of( Map.of( "source", "wiki", "ref", "Page", "helped", "partly" ) ) );
        m.put( "context_missing", "nothing on X" );
        m.put( "friction", List.of( Map.of( "product", "wikantik", "tool", "write_pages", "what", "slow", "blocking", true ) ) );
        m.put( "notes", "n" );
        final AgentReport r = AgentReportValidator.validate( m ).draft();
        assertEquals( "2026-11-26T10:00:00Z", r.windowStart().toString() );
        assertEquals( new ContextUse( "wiki", "Page", "partly" ), r.contextUsed().get( 0 ) );
        assertEquals( new Friction( "wikantik", "write_pages", "slow", true ), r.friction().get( 0 ) );
        assertEquals( List.of( "wikantik", "jakemon" ), r.products() );
    }

    @Test
    void badWindowIsRefused() {
        final Map< String, Object > m = minimal();
        m.put( "window_start", "yesterday" );
        assertRefusedNaming( m, "window_start" );
        final Map< String, Object > n = minimal();
        n.put( "window_start", "2026-11-26T11:00:00Z" );
        n.put( "window_end", "2026-11-26T10:00:00Z" );
        assertRefusedNaming( n, "window_end" );
    }

    @Test
    void wrongTypesAreRefused() {
        final Map< String, Object > m = minimal();
        m.put( "products", "wikantik" );
        assertRefusedNaming( m, "products" );
        final Map< String, Object > n = minimal();
        n.put( "task", 5 );
        assertRefusedNaming( n, "task" );
    }
}

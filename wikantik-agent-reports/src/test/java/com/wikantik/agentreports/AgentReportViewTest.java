package com.wikantik.agentreports;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentReportViewTest {

    @Test
    void rendersEveryFieldWithInstantsAsStrings() {
        final AgentReport r = new AgentReport( 7L, Instant.parse( "2026-11-26T10:00:00Z" ), "claude-code", "abc",
                "jake", "ws", "build", List.of( "wikantik" ), List.of( "tdd" ),
                Instant.parse( "2026-11-26T09:00:00Z" ), Instant.parse( "2026-11-26T09:30:00Z" ), "task", "done",
                List.of( new ContextUse( "wiki", "Page", "yes" ) ), "missing",
                List.of( new Friction( "wikantik", "tool", "what", true ) ), "notes" );
        final Map< String, Object > m = AgentReportView.toMap( r );
        assertEquals( 7L, m.get( "id" ) );
        assertEquals( "2026-11-26T10:00:00Z", m.get( "filed_at" ) );
        assertEquals( "build", m.get( "kind_of_work" ) );
        assertEquals( "2026-11-26T09:00:00Z", m.get( "window_start" ) );
        assertEquals( "missing", m.get( "context_missing" ) );
        assertEquals( Map.of( "source", "wiki", "ref", "Page", "helped", "yes" ),
                ( ( List< ? > ) m.get( "context_used" ) ).get( 0 ) );
        assertEquals( Map.of( "product", "wikantik", "tool", "tool", "what", "what", "blocking", true ),
                ( ( List< ? > ) m.get( "friction" ) ).get( 0 ) );
        assertEquals( 17, m.size() );
    }

    @Test
    void nullInstantsAndListsRenderAsNullAndEmpty() {
        final AgentReport r = new AgentReport( null, null, null, null, null, null, "build", List.of(), List.of(),
                null, null, "task", "done", null, null, null, null );
        final Map< String, Object > m = AgentReportView.toMap( r );
        assertEquals( null, m.get( "filed_at" ) );
        assertEquals( null, m.get( "window_end" ) );
        assertEquals( List.of(), m.get( "context_used" ) );
        assertEquals( List.of(), m.get( "friction" ) );
        assertEquals( List.of( m ), AgentReportView.toMaps( List.of( r ) ) );
    }
}

package com.wikantik.agentreports;

import java.util.List;

/** Outcome of validating a filing: either an acceptable draft or the reasons it was refused. */
public record Validation( AgentReport draft, List< String > reasons ) {

    static Validation ok( final AgentReport draft ) {
        return new Validation( draft, List.of() );
    }

    static Validation refused( final List< String > reasons ) {
        return new Validation( null, List.copyOf( reasons ) );
    }

    public boolean isOk() {
        return draft != null;
    }
}

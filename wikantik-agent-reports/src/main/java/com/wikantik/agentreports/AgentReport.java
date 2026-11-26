package com.wikantik.agentreports;

import java.time.Instant;
import java.util.List;

/**
 * A filed field report. A draft out of {@link AgentReportValidator} carries no id, no filing time and
 * no filer fields; those are filled by the service and the store.
 */
public record AgentReport( Long id, Instant filedAt, String client, String sessionHash, String filedBy,
                           String workspace, String kindOfWork, List< String > products, List< String > skills,
                           Instant windowStart, Instant windowEnd, String task, String outcome,
                           List< ContextUse > contextUsed, String contextMissing,
                           List< Friction > friction, String notes ) {

    /** Copy with the filing time set. */
    AgentReport filedAt( final Instant at ) {
        return new AgentReport( id, at, client, sessionHash, filedBy, workspace, kindOfWork, products, skills,
            windowStart, windowEnd, task, outcome, contextUsed, contextMissing, friction, notes );
    }
}

package com.wikantik.agentreports;

import java.util.List;

/** Append-only storage of reports: there is deliberately no update or delete. */
public interface AgentReportStore {

    /** Stores the report with the server-filled {@code filer} fields, atomically; returns the new id. */
    long insert( AgentReport report, Filer filer );

    /** Reports matching the query, newest first. */
    List< AgentReport > list( ReportQuery query );
}

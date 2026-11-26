package com.wikantik.agentreports;

import java.util.List;

/** What came of a filing attempt. */
public sealed interface FileResult {

    /** The report was stored under {@code id}. */
    record Filed( long id ) implements FileResult { }

    /** The report was invalid; each reason names the offending field. Nothing was stored. */
    record Refused( List< String > reasons ) implements FileResult { }

    /** The filer is over its hourly allowance; try again after {@code retryAfterSeconds}. */
    record RateLimited( long retryAfterSeconds ) implements FileResult { }
}

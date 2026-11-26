package com.wikantik.agentreports;

/** The store could not complete an operation; the cause is the underlying SQL failure. */
public class ReportStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReportStoreException( final String message, final Throwable cause ) {
        super( message, cause );
    }
}

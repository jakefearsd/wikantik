package com.wikantik.work;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The service's view of its {@link WorkListener}: a listener that throws is logged and ignored, because by the time
 * it is told, the state change it observes has already committed and must not be reported to the caller as failed.
 */
final class GuardedListener implements WorkListener {

    private static final Logger LOG = LogManager.getLogger( GuardedListener.class );

    private final WorkListener delegate;

    GuardedListener( final WorkListener delegate ) {
        this.delegate = delegate == null ? new WorkListener() { } : delegate;
    }

    @Override
    public void denied( final Action a ) {
        try {
            delegate.denied( a );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on a denial of {}: {}", a, e.toString(), e );
        }
    }

    @Override
    public void claim( final ClaimOutcome o ) {
        try {
            delegate.claim( o );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on claim outcome {}: {}", o, e.toString(), e );
        }
    }

    @Override
    public void deployment( final String state ) {
        try {
            delegate.deployment( state );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on deployment state {}: {}", state, e.toString(), e );
        }
    }

    @Override
    public void codeHost( final String kind, final String outcome ) {
        try {
            delegate.codeHost( kind, outcome );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on code-host event {} {}: {}", kind, outcome, e.toString(), e );
        }
    }

    @Override
    public void codeHostRejected( final HookRejection reason ) {
        try {
            delegate.codeHostRejected( reason );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on code-host rejection {}: {}", reason, e.toString(), e );
        }
    }

    @Override
    public void gate( final String gate, final boolean allowed ) {
        try {
            delegate.gate( gate, allowed );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on gate {}: {}", gate, e.toString(), e );
        }
    }

    @Override
    public void notified( final String channel, final String outcome ) {
        try {
            delegate.notified( channel, outcome );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on notification {}/{}: {}", channel, outcome, e.toString(), e );
        }
    }

    @Override
    public void notifySkipped() {
        try {
            delegate.notifySkipped();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work listener failed on a skipped notification: {}", e.toString(), e );
        }
    }

    /** Reports {@code n} claims ended by something other than an explicit release or the sweep. */
    void released( final int n ) {
        for ( int i = 0; i < n; i++ ) {
            claim( ClaimOutcome.RELEASED );
        }
    }
}

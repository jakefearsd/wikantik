/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.collab.testing;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * F6: a hand-written {@link AsyncContext} for {@code CollabResource} unit tests. It records {@link #complete()} (failing
 * with an {@link AssertionError} on a second call: every request is answered exactly once), lets the test fire the
 * long-poll timeout explicitly ({@link #fireTimeout()}), and {@link #assertCompletedOnce()} catches a request that was
 * never answered. No container: {@link #start} runs the task inline and {@code dispatch} is unsupported (the collab
 * resource never dispatches). Thread-safe, so a threaded test may complete it from another thread.
 */
public final class FakeAsyncContext implements AsyncContext {

    private final ServletRequest request;
    private final ServletResponse response;
    private final List< AsyncListener > listeners = new ArrayList<>();
    private int completions;
    private long timeout = 30_000;

    public FakeAsyncContext() {
        this( null, null );
    }

    public FakeAsyncContext( final ServletRequest request, final ServletResponse response ) {
        this.request = request;
        this.response = response;
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return true;
    }

    @Override
    public void dispatch() {
        throw new UnsupportedOperationException( "FakeAsyncContext does not dispatch" );
    }

    @Override
    public void dispatch( final String path ) {
        throw new UnsupportedOperationException( "FakeAsyncContext does not dispatch" );
    }

    @Override
    public void dispatch( final ServletContext context, final String path ) {
        throw new UnsupportedOperationException( "FakeAsyncContext does not dispatch" );
    }

    /** Records the completion and notifies the listeners' {@code onComplete}; a second call fails. */
    @Override
    public void complete() {
        final List< AsyncListener > notify;
        synchronized( this ) {
            completions++;
            if( completions > 1 ) {
                throw new AssertionError( "AsyncContext.complete() called twice (" + completions
                    + " calls): a request must be answered exactly once" );
            }
            notify = List.copyOf( listeners );
        }
        final AsyncEvent event = new AsyncEvent( this, request, response );
        for( final AsyncListener l : notify ) {
            try {
                l.onComplete( event );
            } catch( final java.io.IOException e ) {
                throw new AssertionError( "onComplete listener failed: " + e.getMessage(), e );
            }
        }
    }

    /**
     * What the container does when the async timeout elapses: invokes every listener's {@code onTimeout}. It does not
     * complete the request itself; the listener must. Firing after completion fails (the container would not).
     */
    public void fireTimeout() {
        final List< AsyncListener > notify;
        synchronized( this ) {
            if( completions > 0 ) {
                throw new AssertionError( "timeout fired on an AsyncContext that was already completed" );
            }
            notify = List.copyOf( listeners );
        }
        final AsyncEvent event = new AsyncEvent( this, request, response );
        for( final AsyncListener l : notify ) {
            try {
                l.onTimeout( event );
            } catch( final java.io.IOException e ) {
                throw new AssertionError( "onTimeout listener failed: " + e.getMessage(), e );
            }
        }
    }

    /** Fails unless {@link #complete()} was called exactly once. */
    public synchronized void assertCompletedOnce() {
        if( completions == 0 ) {
            throw new AssertionError( "AsyncContext was never completed: the request was never answered" );
        }
        if( completions > 1 ) {
            throw new AssertionError( "AsyncContext was completed " + completions + " times" );
        }
    }

    public synchronized boolean isCompleted() {
        return completions > 0;
    }

    /** How many times {@link #complete()} was called (including a failing second call). */
    public synchronized int completions() {
        return completions;
    }

    /** The registered listeners, in order (a copy). */
    public synchronized List< AsyncListener > listeners() {
        return List.copyOf( listeners );
    }

    @Override
    public void start( final Runnable run ) {
        run.run();
    }

    @Override
    public synchronized void addListener( final AsyncListener listener ) {
        listeners.add( listener );
    }

    @Override
    public synchronized void addListener( final AsyncListener listener, final ServletRequest servletRequest,
                                          final ServletResponse servletResponse ) {
        listeners.add( listener );
    }

    @Override
    public < T extends AsyncListener > T createListener( final Class< T > clazz ) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch( final ReflectiveOperationException e ) {
            throw new ServletException( "cannot instantiate listener " + clazz.getName(), e );
        }
    }

    @Override
    public synchronized void setTimeout( final long millis ) {
        this.timeout = millis;
    }

    @Override
    public synchronized long getTimeout() {
        return timeout;
    }
}

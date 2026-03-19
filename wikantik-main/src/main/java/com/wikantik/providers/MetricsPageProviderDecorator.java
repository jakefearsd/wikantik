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
package com.wikantik.providers;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A PageProvider decorator that collects performance metrics.
 * <p>
 * This decorator tracks call counts and cumulative time for each provider operation,
 * making it useful for performance analysis and identifying bottlenecks.
 * <p>
 * Metrics can be retrieved via {@link #getMetrics()} and reset via {@link #resetMetrics()}.
 * <p>
 * Example usage:
 * <pre>
 * MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator(
 *     new VersioningFileProvider()
 * );
 * // ... use provider ...
 * Metrics metrics = metricsProvider.getMetrics();
 * System.out.println("Total getPageText calls: " + metrics.getPageTextCalls);
 * </pre>
 *
 * @since 2.12.3
 */
public class MetricsPageProviderDecorator extends PageProviderDecorator {

    private final Metrics metrics = new Metrics();

    /**
     * Holds performance metrics for provider operations.
     */
    public static class Metrics {
        public final AtomicLong getPageTextCalls = new AtomicLong();
        public final AtomicLong getPageTextTimeNanos = new AtomicLong();

        public final AtomicLong getPageInfoCalls = new AtomicLong();
        public final AtomicLong getPageInfoTimeNanos = new AtomicLong();

        public final AtomicLong pageExistsCalls = new AtomicLong();
        public final AtomicLong pageExistsTimeNanos = new AtomicLong();

        public final AtomicLong putPageTextCalls = new AtomicLong();
        public final AtomicLong putPageTextTimeNanos = new AtomicLong();

        public final AtomicLong getAllPagesCalls = new AtomicLong();
        public final AtomicLong getAllPagesTimeNanos = new AtomicLong();

        public final AtomicLong getVersionHistoryCalls = new AtomicLong();
        public final AtomicLong getVersionHistoryTimeNanos = new AtomicLong();

        public final AtomicLong deletePageCalls = new AtomicLong();
        public final AtomicLong deletePageTimeNanos = new AtomicLong();

        public final AtomicLong deleteVersionCalls = new AtomicLong();
        public final AtomicLong deleteVersionTimeNanos = new AtomicLong();

        public final AtomicLong movePageCalls = new AtomicLong();
        public final AtomicLong movePageTimeNanos = new AtomicLong();

        /**
         * Resets all metrics to zero.
         */
        public void reset() {
            getPageTextCalls.set( 0 );
            getPageTextTimeNanos.set( 0 );
            getPageInfoCalls.set( 0 );
            getPageInfoTimeNanos.set( 0 );
            pageExistsCalls.set( 0 );
            pageExistsTimeNanos.set( 0 );
            putPageTextCalls.set( 0 );
            putPageTextTimeNanos.set( 0 );
            getAllPagesCalls.set( 0 );
            getAllPagesTimeNanos.set( 0 );
            getVersionHistoryCalls.set( 0 );
            getVersionHistoryTimeNanos.set( 0 );
            deletePageCalls.set( 0 );
            deletePageTimeNanos.set( 0 );
            deleteVersionCalls.set( 0 );
            deleteVersionTimeNanos.set( 0 );
            movePageCalls.set( 0 );
            movePageTimeNanos.set( 0 );
        }

        /**
         * Returns average time in milliseconds for getPageText calls.
         */
        public double getAverageGetPageTextMs() {
            final long calls = getPageTextCalls.get();
            return calls == 0 ? 0 : (getPageTextTimeNanos.get() / 1_000_000.0) / calls;
        }

        /**
         * Returns average time in milliseconds for getPageInfo calls.
         */
        public double getAverageGetPageInfoMs() {
            final long calls = getPageInfoCalls.get();
            return calls == 0 ? 0 : (getPageInfoTimeNanos.get() / 1_000_000.0) / calls;
        }

        @Override
        public String toString() {
            return String.format(
                    "Metrics[getPageText=%d (avg %.2fms), getPageInfo=%d (avg %.2fms), " +
                            "pageExists=%d, putPageText=%d, getAllPages=%d, getVersionHistory=%d]",
                    getPageTextCalls.get(), getAverageGetPageTextMs(),
                    getPageInfoCalls.get(), getAverageGetPageInfoMs(),
                    pageExistsCalls.get(), putPageTextCalls.get(),
                    getAllPagesCalls.get(), getVersionHistoryCalls.get()
            );
        }
    }

    /**
     * Creates a metrics decorator wrapping the given provider.
     *
     * @param delegate the provider to wrap
     */
    public MetricsPageProviderDecorator( final PageProvider delegate ) {
        super( delegate );
    }

    /**
     * Returns the current metrics.
     *
     * @return the metrics object
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Resets all metrics to zero.
     */
    public void resetMetrics() {
        metrics.reset();
    }

    @Override
    public String getProviderInfo() {
        return "Metrics decorator for: " + super.getProviderInfo() + " | " + metrics;
    }

    @Override
    public void putPageText( final Page page, final String text ) throws ProviderException {
        metrics.putPageTextCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            super.putPageText( page, text );
        } finally {
            metrics.putPageTextTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public boolean pageExists( final String page ) {
        metrics.pageExistsCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.pageExists( page );
        } finally {
            metrics.pageExistsTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public boolean pageExists( final String page, final int version ) {
        metrics.pageExistsCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.pageExists( page, version );
        } finally {
            metrics.pageExistsTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        metrics.getPageInfoCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.getPageInfo( page, version );
        } finally {
            metrics.getPageInfoTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public Collection<Page> getAllPages() throws ProviderException {
        metrics.getAllPagesCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.getAllPages();
        } finally {
            metrics.getAllPagesTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public List<Page> getVersionHistory( final String page ) throws ProviderException {
        metrics.getVersionHistoryCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.getVersionHistory( page );
        } finally {
            metrics.getVersionHistoryTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public String getPageText( final String page, final int version ) throws ProviderException {
        metrics.getPageTextCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            return super.getPageText( page, version );
        } finally {
            metrics.getPageTextTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public void deleteVersion( final String page, final int version ) throws ProviderException {
        metrics.deleteVersionCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            super.deleteVersion( page, version );
        } finally {
            metrics.deleteVersionTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public void deletePage( final String page ) throws ProviderException {
        metrics.deletePageCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            super.deletePage( page );
        } finally {
            metrics.deletePageTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }

    @Override
    public void movePage( final String from, final String to ) throws ProviderException {
        metrics.movePageCalls.incrementAndGet();
        final long start = System.nanoTime();
        try {
            super.movePage( from, to );
        } finally {
            metrics.movePageTimeNanos.addAndGet( System.nanoTime() - start );
        }
    }
}

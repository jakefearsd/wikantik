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
package com.wikantik.knowledge.querylog;

import com.wikantik.api.briefing.BriefingLogService;

import javax.sql.DataSource;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the {@link BriefingLogService} at startup. Logging is ON by default
 * ({@code wikantik.briefing.log.enabled}); writes run on a single bounded-queue daemon thread
 * that drops the oldest pending record when saturated, so a traffic spike can never
 * back-pressure briefing assembly.
 */
public final class BriefingLogWiring {

    /** Master switch; default {@code true}. */
    public static final String ENABLED_KEY = "wikantik.briefing.log.enabled";
    private static final int QUEUE_CAPACITY = 1_000;

    private BriefingLogWiring() {}

    /** A {@link BriefingLogService} backed by {@code dataSource}, enabled per config (default on). */
    public static BriefingLogService build( final DataSource dataSource, final Properties props ) {
        final boolean enabled = props == null
            || Boolean.parseBoolean( props.getProperty( ENABLED_KEY, "true" ) );
        return new JdbcBriefingLogService( dataSource, enabled, writerExecutor() );
    }

    /** One daemon writer thread + a bounded queue; discard-oldest on overflow (fail-open). */
    private static Executor writerExecutor() {
        final AtomicInteger n = new AtomicInteger();
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>( QUEUE_CAPACITY ),
            r -> {
                final Thread t = new Thread( r, "briefinglog-writer-" + n.incrementAndGet() );
                t.setDaemon( true );
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() );
        pool.allowCoreThreadTimeOut( true );
        return pool;
    }
}

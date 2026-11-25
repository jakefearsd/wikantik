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
package com.wikantik.insights.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the content-intelligence background timers so engine shutdown can stop them. Registered in the engine's
 * service registry, which closes it from {@code WikiEngine#shutdown()}.
 */
public final class InsightsBackground implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( InsightsBackground.class );

    private final List< AutoCloseable > parts = new CopyOnWriteArrayList<>();

    void add( final AutoCloseable part ) {
        parts.add( part );
    }

    boolean isEmpty() {
        return parts.isEmpty();
    }

    @Override
    public void close() {
        for ( final AutoCloseable part : parts ) {
            try {
                part.close();
            } catch ( final Exception e ) {
                LOG.warn( "insights background part {} failed to close: {}", part.getClass().getSimpleName(),
                        e.getMessage(), e );
            }
        }
        parts.clear();
    }
}

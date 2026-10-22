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
package com.wikantik.ontology.runtime;

import com.wikantik.ontology.OntologyModelManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The ontology runtime's closeable resources, registered on the engine by
 * {@link OntologyWiringHelper} so {@code WikiEngine.shutdown()} can release them.
 *
 * @param scheduler  periodic rebuild/compaction timer
 * @param pageSync   page-event worker (drains what is queued)
 * @param entitySync KG-event worker; {@code null} when incremental entity sync is off
 * @param manager    the TDB2 dataset — closed last, after everything that writes to it
 */
public record OntologyRuntime( OntologyRebuildScheduler scheduler,
                               OntologyPageSyncQueue pageSync,
                               OntologyEntitySync entitySync,
                               OntologyModelManager manager ) implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( OntologyRuntime.class );

    /**
     * Stops the timer, drains the workers, then closes the dataset. Never throws: a failing step
     * is logged and the rest still run. A rebuild or compaction already running on its own daemon
     * thread is not waited for; TDB2 discards its uncommitted transaction as it would after a crash.
     */
    @Override
    public void close() {
        step( "rebuild/compaction scheduler", scheduler, OntologyRebuildScheduler::stop );
        step( "page sync worker", pageSync, OntologyPageSyncQueue::close );
        step( "entity sync worker", entitySync, OntologyEntitySync::close );
        step( "TDB2 dataset", manager, OntologyModelManager::close );
    }

    private static < T > void step( final String what, final T resource, final java.util.function.Consumer< T > closer ) {
        if ( resource == null ) {
            return;
        }
        try {
            closer.accept( resource );
        } catch ( final RuntimeException e ) {
            LOG.warn( "ontology shutdown: closing the {} failed: {}", what, e.getMessage(), e );
        }
    }
}

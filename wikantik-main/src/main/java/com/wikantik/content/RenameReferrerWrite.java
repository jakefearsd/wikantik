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
package com.wikantik.content;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One referrer's link rewrite during a rename. The read, rewrite and {@code putPageText} run under the referrer's save
 * lock, so a guarded save of the referrer cannot land between the read and the write and then be overwritten by the
 * rewrite of the text it replaced. The references are updated after the lock is released. A failure is logged and the
 * rename goes on with the other referrers.
 */
final class RenameReferrerWrite {

    private static final Logger LOG = LogManager.getLogger( RenameReferrerWrite.class );

    private RenameReferrerWrite() {
    }

    /**
     * @param lifecycle the page lifecycle holding the save locks, or {@code null} when there is none
     * @param referrer  the referring page
     * @param rename    "old ==> new", for the log
     * @param rewrite   the read, rewrite and put; returns the page written, or {@code null} when nothing changed
     */
    static void write( final Engine engine, final PageLifecycle lifecycle, final String referrer, final String rename,
                       final PageLifecycle.PageLockedAction< Page > rewrite ) {
        final Page written;
        try {
            written = PageLifecycle.underPageLock( lifecycle, referrer, rewrite );
        } catch( final WikiException e ) {
            // LOG.error justified: the rename went ahead but this referrer keeps a dangling link, needing an operator
            LOG.error( "Unable to rewrite the links of referrer '{}' for the rename {}: {}", referrer, rename, e.getMessage(), e );
            return;
        }
        if( written != null ) {
            PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().updateReferences( written );
        }
    }
}

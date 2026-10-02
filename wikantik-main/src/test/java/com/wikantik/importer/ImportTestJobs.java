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
package com.wikantik.importer;

import java.util.List;
import java.util.Map;

/** Builds import jobs over an empty plan for registry tests. Shipped in the wikantik-main test-jar. */
public final class ImportTestJobs {

    private ImportTestJobs() {
    }

    /** A no-op sink: nothing exists, saves succeed, attachments are discarded. */
    public static ImportPageSink noopSink() {
        return new ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return false;
            }
            @Override public List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote ) {
                return List.of();
            }
            @Override public void storeAttachment( final String page, final String fileName,
                    final java.io.InputStream in, final String author ) {
                // discarded
            }
        };
    }

    /** A job over an empty plan with a throwaway upload. */
    public static VaultImportJob job( final String id, final String owner ) {
        try {
            return job( id, owner, TestVaults.upload( TestVaults.zipText( Map.of() ), "empty.zip" ) );
        } catch ( final Exception e ) {
            throw new IllegalStateException( e );
        }
    }

    /** A job over an empty plan with the given upload. */
    public static VaultImportJob job( final String id, final String owner, final SpooledUpload upload ) {
        final PlanTotals totals = new PlanTotals( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 );
        final ImportPlan plan = new ImportPlan( "h", "empty", ClusterMode.NONE, totals, List.of(), List.of(),
            List.of(), Map.of() );
        return new VaultImportJob( id, owner, owner, upload, new PlanResult( plan, List.of(), List.of() ),
            noopSink(), () -> true );
    }
}

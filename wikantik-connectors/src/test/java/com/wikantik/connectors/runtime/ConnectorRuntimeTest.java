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
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectorRuntimeTest {

    private static SourceConnector connector( String id ) {
        return new SourceConnector() {
            public String connectorId() { return id; }
            public SyncBatch poll( SyncCursor c ) {
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
    }

    private static ConnectorRuntime runtime( DataSource ds, SourceConnector... cs ) {
        Map<String,SourceConnector> byId = new LinkedHashMap<>();
        Map<String,String> typeById = new LinkedHashMap<>();
        for ( SourceConnector c : cs ) { byId.put( c.connectorId(), c ); typeById.put( c.connectorId(), "filesystem" ); }
        ConnectorRegistry reg = new ConnectorRegistry( byId, typeById );
        // A real orchestrator over fake state + fake sink so syncNow returns a real SyncReport.
        SyncStateStore store = mock( SyncStateStore.class );
        when( store.loadCursor( anyString() ) ).thenReturn( Optional.empty() );
        when( store.syncedHash( anyString(), anyString() ) ).thenReturn( Optional.empty() );
        when( store.knownUris( anyString() ) ).thenReturn( List.of() );
        DerivedPageSink sink = mock( DerivedPageSink.class );
        SyncOrchestrator orch = new SyncOrchestrator( store, sink );
        return new ConnectorRuntime( reg, orch, new ConnectorStatusReader( ds ) );
    }

    @Test void syncNowRunsRegisteredConnector() {
        // ds unused by syncNow; pass a mock
        SyncReport r = runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "fs1" );
        assertNotNull( r );          // empty fixture → 0 of everything, but a real report
        assertEquals( 0, r.created() + r.updated() + r.unchanged() + r.deleted() + r.failed() );
    }

    @Test void syncNowUnknownIdThrows() {
        assertThrows( IllegalArgumentException.class,
            () -> runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "nope" ) );
    }

    @Test void schedulerDisabledWhenIntervalNonPositive() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startScheduler( 0 );      // must NOT start a thread
        assertFalse( rt.isSchedulerRunning() );
        rt.stop();                   // safe even when never started
    }

    @Test void schedulerStartsWhenIntervalPositive() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startScheduler( 24 );
        assertTrue( rt.isSchedulerRunning() );
        rt.stop();
        assertFalse( rt.isSchedulerRunning() );
    }
}

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
package com.wikantik.derived;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorWiringHelperTest {

    @Test void parsesFilesystemRootsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.filesystem.docs.root", "/data/docs" );
        p.setProperty( "wikantik.connectors.filesystem.wiki-src.root", "/data/wiki" );
        p.setProperty( "wikantik.connectors.filesystem.docs.ignored", "x" ); // non-.root key ignored
        Map< String, String > roots = ConnectorWiringHelper.filesystemRoots( p );
        assertEquals( 2, roots.size() );
        assertEquals( "/data/docs", roots.get( "docs" ) );
        assertEquals( "/data/wiki", roots.get( "wiki-src" ) );
    }

    @Test void disabledByDefaultReturnsEmpty() {
        // enabled flag absent → wireConnectors is a no-op. Pass nulls for collaborators it must not touch.
        assertTrue( ConnectorWiringHelper.wireConnectors( null, new Properties(), null, null, null ).isEmpty() );
    }

    @Test void noFilesystemRootsMeansEmptyMap() {
        assertTrue( ConnectorWiringHelper.filesystemRoots( new Properties() ).isEmpty() );
    }
}

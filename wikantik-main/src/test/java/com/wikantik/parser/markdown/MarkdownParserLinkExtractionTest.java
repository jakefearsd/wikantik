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
package com.wikantik.parser.markdown;

import com.wikantik.TestEngine;
import com.wikantik.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;

public class MarkdownParserLinkExtractionTest {

    TestEngine engine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception {
        engine.stop();
    }

    @Test
    public void testLinksInsideTablesExtracted() throws Exception {
        engine.saveText( "TableLinkPage",
                "| Column |\n| --- |\n| [TableTarget]() |\n" );

        final ReferenceManager refMgr = engine.getManager( ReferenceManager.class );
        final Collection< String > refs = refMgr.findReferrers( "TableTarget" );
        Assertions.assertNotNull( refs );
        Assertions.assertTrue( refs.contains( "TableLinkPage" ) );
    }

    @Test
    public void testLinksInsideFootnotesExtracted() throws Exception {
        engine.saveText( "FootnoteLinkPage",
                "Main text[^1].\n\n[^1]: See [FootnoteTarget]() for more." );

        final ReferenceManager refMgr = engine.getManager( ReferenceManager.class );
        final Collection< String > refs = refMgr.findReferrers( "FootnoteTarget" );
        Assertions.assertNotNull( refs );
        Assertions.assertTrue( refs.contains( "FootnoteLinkPage" ) );
    }
}

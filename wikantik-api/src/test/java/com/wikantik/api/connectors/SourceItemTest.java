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
package com.wikantik.api.connectors;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceItemTest {
    @Test void carriesUriMetadataAndAclRefs() {
        SourceItem i = new SourceItem( "file:docs/a.md", "body".getBytes(), "text/markdown",
            Map.of( "path", "docs/a.md" ), List.of( "group:docs" ), "abc123" );
        assertEquals( "file:docs/a.md", i.sourceUri() );
        assertEquals( List.of( "group:docs" ), i.aclRefs() );
        assertEquals( "abc123", i.contentHash() );
    }

    @Test void syncBatchHoldsItemsTombstonesCursorAndCompleteFlag() {
        SyncBatch b = new SyncBatch( List.of(), List.of( "file:gone.md" ), new SyncCursor( "c1" ), true );
        assertTrue( b.complete() );
        assertEquals( List.of( "file:gone.md" ), b.tombstonedUris() );
        assertEquals( "c1", b.nextCursor().value() );
    }
}

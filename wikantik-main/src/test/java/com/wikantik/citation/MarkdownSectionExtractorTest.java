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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarkdownSectionExtractorTest {

    private final MarkdownSectionExtractor ex = new MarkdownSectionExtractor();
    private static final String BODY =
          "# Deploy\n"
        + "intro line\n"
        + "## Rollback Steps\n"
        + "Always drain the queue before rollback.\n"
        + "Then flip the flag.\n"
        + "## Other\n"
        + "unrelated\n";

    @Test
    void returnsTextUnderMatchedHeadingPath() {
        final Optional< String > s = ex.sectionText( BODY, "Deploy > Rollback Steps" );
        assertTrue( s.isPresent() );
        assertTrue( s.get().contains( "Always drain the queue before rollback" ) );
        assertTrue( s.get().contains( "Then flip the flag" ) );
        assertFalse( s.get().contains( "unrelated" ) );
    }

    @Test
    void emptyHeadingPathReturnsWholeBody() {
        assertTrue( ex.sectionText( BODY, "" ).orElse( "" ).contains( "unrelated" ) );
    }

    @Test
    void unknownHeadingPathIsEmpty() {
        assertTrue( ex.sectionText( BODY, "Deploy > Nonexistent" ).isEmpty() );
    }
}

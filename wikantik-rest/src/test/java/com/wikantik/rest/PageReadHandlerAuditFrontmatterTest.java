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
package com.wikantik.rest;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.parser.markdown.ReaderText;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The read-audit policy must decide on the page's real frontmatter, whatever the reader is shown. */
class PageReadHandlerAuditFrontmatterTest {

    private static Map< String, Object > forReader( final boolean canEdit, final String raw, final boolean latest ) {
        final String aclVisible = AclDirectiveText.forViewer( canEdit, raw );
        final ParsedPage parsed = FrontmatterParser.parse( ReaderText.forViewer( canEdit, aclVisible ) );
        return PageReadHandler.latestFrontmatterForAudit( latest, raw, aclVisible, parsed );
    }

    @Test
    void aPlainPagesParsedFrontmatterIsReused() {
        final String raw = "---\naudit_reads: true\n---\nBody %% a comment %% text.\n";
        final String aclVisible = AclDirectiveText.forViewer( false, raw );
        final ParsedPage parsed = FrontmatterParser.parse( ReaderText.forViewer( false, aclVisible ) );
        assertSame( parsed.metadata(), PageReadHandler.latestFrontmatterForAudit( true, raw, aclVisible, parsed ),
                "nothing was stripped from the frontmatter, so the reader's parse is the real one" );
        assertEquals( Boolean.TRUE, parsed.metadata().get( "audit_reads" ) );
    }

    /**
     * A non-editor's view has access rules stripped, frontmatter values included; stripping can even drop the whole
     * block (a removal that would end it early). The policy must decide on what the page really says, so when
     * anything was stripped it gets the raw frontmatter, never the reader's view.
     */
    @Test
    void whenAnAccessRuleWasStrippedThePolicyGetsTheRawFrontmatterNotTheReadersView() {
        final String raw = "---\naudit_reads: true\nsummary: \"Shared with [{ALLOW view alice}] only\"\n---\nBody.\n";
        final Map< String, Object > forPolicy = forReader( false, raw, true );
        assertEquals( Boolean.TRUE, forPolicy.get( "audit_reads" ) );
        assertEquals( "Shared with [{ALLOW view alice}] only", forPolicy.get( "summary" ),
                "the policy sees the page's real frontmatter, not the reader's stripped copy" );
        assertEquals( Boolean.TRUE, forReader( true, raw, true ).get( "audit_reads" ) );
    }

    @Test
    void anOlderVersionLetsThePolicyLookUpTheLatestFrontmatter() {
        assertNull( forReader( false, "---\naudit_reads: true\n---\nBody.\n", false ) );
    }
}

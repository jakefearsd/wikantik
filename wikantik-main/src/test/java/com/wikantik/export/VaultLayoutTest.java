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
package com.wikantik.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;

import static org.junit.jupiter.api.Assertions.*;

class VaultLayoutTest {
    private static PageDescriptor p( final String slug, final String cluster ) {
        return new PageDescriptor( "ID-" + slug, slug, slug, PageType.ARTICLE, cluster,
                cluster == null ? List.of() : List.of( cluster ), List.of(), "", Instant.EPOCH, Optional.empty(), false );
    }

    @Test void pathsFollowPrimaryCluster() {
        final VaultLayout l = VaultLayout.plan( List.of( p( "Roth", "finance/retirement" ), p( "Loose", null ) ), List.of() );
        assertEquals( "finance/retirement/Roth.md", l.pagePath( "Roth" ) );
        assertEquals( "_unclustered/Loose.md", l.pagePath( "Loose" ) );
    }

    @Test void caseInsensitiveCollisionSuffixedWithAlias() {
        final VaultLayout l = VaultLayout.plan( List.of( p( "FooBar", "a" ), p( "Foobar", "b" ) ), List.of() );
        assertEquals( "a/FooBar.md", l.pagePath( "FooBar" ) );
        assertEquals( "b/Foobar~2.md", l.pagePath( "Foobar" ) );
        assertEquals( Optional.of( "Foobar" ), l.aliasFor( "Foobar" ) );
        assertEquals( Optional.empty(), l.aliasFor( "FooBar" ) );
        assertEquals( "FooBar", l.basename( "FooBar" ) );
        assertEquals( "Foobar~2", l.basename( "Foobar" ) );
        assertNull( l.basename( "NotInExport" ) );
    }

    /** A {@code cluster:} value is only WARNING-validated, so it can carry traversal segments. */
    @Test void traversalClusterStaysInsideVault() {
        final VaultLayout l = VaultLayout.plan( List.of( p( "Evil", "../../evil" ), p( "Dots", "./a//b/.." ),
                p( "Lead", "/abs" ) ), List.of() );
        assertEquals( "_/_/evil/Evil.md", l.pagePath( "Evil" ) );
        assertEquals( "_/a/_/b/_/Dots.md", l.pagePath( "Dots" ) );
        assertEquals( "_/abs/Lead.md", l.pagePath( "Lead" ) );
    }

    @Test void traversalAttachmentAndPageNamesStayInsideVault() {
        final AttachmentRef evil = new AttachmentRef( "A", "../x.png" );
        final AttachmentRef dots = new AttachmentRef( "..", ".." );
        final VaultLayout l = VaultLayout.plan( List.of( p( "A", "x" ), p( "..", "x" ) ), List.of( evil, dots ) );
        assertEquals( "_attachments/A/.._x.png", l.attachmentPath( evil ) );
        assertEquals( "_attachments/_/_", l.attachmentPath( dots ) );
        assertEquals( "x/_.md", l.pagePath( ".." ) );
        assertEquals( ".._x.png", l.attachmentLinkTarget( evil ) );
    }

    @Test void illegalCharactersSanitised() {
        assertEquals( "What_ Why_", VaultLayout.sanitize( "What: Why?" ) );
    }

    @Test void attachmentTargetsQualifiedOnlyWhenAmbiguous() {
        final AttachmentRef a = new AttachmentRef( "A", "chart.png" ), b = new AttachmentRef( "B", "Chart.png" ), c = new AttachmentRef( "A", "only.pdf" );
        final VaultLayout l = VaultLayout.plan( List.of( p( "A", "x" ), p( "B", "x" ) ), List.of( a, b, c ) );
        assertEquals( "_attachments/A/chart.png", l.attachmentPath( a ) );
        assertEquals( "_attachments/A/chart.png", l.attachmentLinkTarget( a ) );
        assertEquals( "_attachments/B/Chart.png", l.attachmentLinkTarget( b ) );
        assertEquals( "only.pdf", l.attachmentLinkTarget( c ) );
    }
}

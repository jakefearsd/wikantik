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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailDigestTest {

    private static final String BASE = "https://wiki.example.com";
    private static final Function< String, String > NAMES = login -> switch ( login ) {
        case "lena" -> "Lena Lead";
        default -> "Alice A";
    };
    private static final Instant AT = Instant.parse( "2026-03-02T10:00:00Z" );

    private static WorkEvent event( final long id, final Long item, final String itemKey, final String type,
                                    final String to, final String note ) {
        return new WorkEvent( id, 1L, item, itemKey, AT, "lena", Actor.Kind.HUMAN, null, type, null, to, note );
    }

    private static PendingEmail row( final long id, final String itemKey, final String title, final String type,
                                     final String to, final String comment ) {
        final Long item = itemKey == null ? null : 5L;
        final NoticeView view = new NoticeView( id, NoticeReason.WATCHING, AT, null,
            event( id * 10, item, itemKey, type, to, null ), "PLAT", title );
        return new PendingEmail( "otto", EmailMode.EACH, null, view, comment );
    }

    private static EmailDigest.Email render( final EmailDetail detail, final PendingEmail... rows ) {
        return EmailDigest.render( List.of( rows ), detail, BASE, NAMES );
    }

    @Test
    void summaryCarriesNoCommentOrBodyText() {
        final EmailDigest.Email mail = render( EmailDetail.SUMMARY,
            row( 1, "PLAT-12", "Fix it", WorkEvent.COMMENTED, "7", "secret words in a comment" ) );
        assertFalse( mail.text().contains( "secret words" ) );
        assertTrue( mail.text().contains( "Lena Lead commented" ) );
    }

    @Test
    void fullCarriesTheCommentCutAt500() {
        final String body = "x".repeat( 700 );
        final EmailDigest.Email mail = render( EmailDetail.FULL,
            row( 1, "PLAT-12", "Fix it", WorkEvent.COMMENTED, "7", body ) );
        assertTrue( mail.text().contains( "    > " + "x".repeat( 500 ) + "…\n" ), mail.text() );
        assertFalse( mail.text().contains( "x".repeat( 501 ) ) );
    }

    @Test
    void mentionTokensBecomeNames() {
        final EmailDigest.Email mail = render( EmailDetail.FULL, row( 1, "PLAT-12", "Fix it", WorkEvent.COMMENTED, "7",
            "hi @[Pia Person](user:abc-123), see this\nsecond line" ) );
        assertTrue( mail.text().contains( "    > hi @Pia Person, see this\n    > second line\n" ), mail.text() );
        assertFalse( mail.text().contains( "abc-123" ) );
    }

    @Test
    void itemsAreGroupedAndNoticesOldestFirst() {
        final EmailDigest.Email mail = render( EmailDetail.SUMMARY,
            row( 3, "PLAT-2", "Second", WorkEvent.SHIPPED, null, null ),
            row( 1, "PLAT-1", "First", WorkEvent.REVIEW_REQUESTED, null, null ),
            row( 2, "PLAT-1", "First", WorkEvent.CHANGE_MERGED, null, null ) );
        assertEquals( "Work: 3 updates on 2 items", mail.subject() );
        assertEquals( "PLAT-1 First\n- Lena Lead asked for a review\n- Lena Lead merged a change\n"
            + "  https://wiki.example.com/work/item/PLAT-1\n\nPLAT-2 Second\n- Lena Lead shipped this\n"
            + "  https://wiki.example.com/work/item/PLAT-2\n\n"
            + "You get these because you watch these items, were assigned them or were mentioned.\n"
            + "Choose how often at https://wiki.example.com/work/inbox\n", mail.text() );
    }

    @Test
    void theSubjectNeverCarriesATitle() {
        assertEquals( "Work: 1 update on PLAT-12",
            render( EmailDetail.SUMMARY, row( 1, "PLAT-12", "Top secret plan", WorkEvent.SHIPPED, null, null ) ).subject() );
        assertEquals( "Work: 2 updates on PLAT-12", render( EmailDetail.SUMMARY,
            row( 1, "PLAT-12", "Top secret plan", WorkEvent.SHIPPED, null, null ),
            row( 2, "PLAT-12", "Top secret plan", WorkEvent.SHIPPED, null, null ) ).subject() );
    }

    @Test
    void aTitleWithANewlineStaysOnOneLine() {
        final EmailDigest.Email mail = render( EmailDetail.SUMMARY,
            row( 1, "PLAT-12", "Line one\r\nBcc: evil@example.com", WorkEvent.SHIPPED, null, null ) );
        assertTrue( mail.text().startsWith( "PLAT-12 Line one Bcc: evil@example.com\n" ), mail.text() );
    }

    @Test
    void aSwitchedOffWebhookReadsAsSuch() {
        final WorkEvent ev = new WorkEvent( 10, 1L, null, null, AT, "lena", Actor.Kind.HUMAN, null, WorkEvent.SETTINGS,
            "hooks.example.com", "failed", "webhook" );
        final PendingEmail off = new PendingEmail( "otto", EmailMode.EACH, null,
            new NoticeView( 1, NoticeReason.WEBHOOK_DISABLED, AT, null, ev, "PLAT", null ), null );
        final EmailDigest.Email mail = render( EmailDetail.SUMMARY, off );
        assertEquals( "Work: a webhook was switched off", mail.subject() );
        assertTrue( mail.text().contains( "The webhook to hooks.example.com was switched off" ), mail.text() );
        assertFalse( mail.text().contains( "/work/item/" ) );
    }

    @Test
    void noLoginAppears() {
        final PendingEmail assigned = new PendingEmail( "otto", EmailMode.EACH, null, new NoticeView( 1,
            NoticeReason.ASSIGNED, AT, null, new WorkEvent( 10, 1L, 5L, "PLAT-12", AT, "lena", Actor.Kind.HUMAN, null,
                WorkEvent.ASSIGNED, null, "carol-login", null ), "PLAT", "Fix it" ), null );
        final EmailDigest.Email mail = render( EmailDetail.FULL, assigned );
        assertFalse( mail.text().contains( "lena" ) );
        assertFalse( mail.text().contains( "otto" ) );
        assertTrue( mail.text().contains( "assigned this to Alice A" ), mail.text() );
    }
}

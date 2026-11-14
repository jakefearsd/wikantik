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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Save-time guard: an authored canonical_id must not re-key a page whose slug is already bound to another id. */
@RequiresPostgres
class StructuralSpineCanonicalIdGuardTest {

    private static final String X = "01HAA000000000000000000001";
    private static final String Y = "01HAA000000000000000000002";
    private static final String Z = "01HAA000000000000000000003";

    private PageCanonicalIdsDao dao;
    private StructuralSpinePageFilter filter;
    private Context ctx;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "page_canonical_ids", "page_slug_history" );
        dao = new PageCanonicalIdsDao( PostgresTestDb.createDataSource() );
        ctx = mock( Context.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "MyPage" );
        when( ctx.getPage() ).thenReturn( page );
        // The in-memory projection may already hold the rogue id; the guard must still consult the DB.
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.resolveCanonicalIdFromSlug( "MyPage" ) ).thenReturn( Optional.of( Y ) );
        filter = new StructuralSpinePageFilter( svc, n -> false, new Properties(), dao );
    }

    private static String page( final String id, final String eol ) {
        return "---" + eol + "canonical_id: " + id + eol + "title: \"A  spaced: title\"" + eol
                + "tags: [a,   b]" + eol + "---" + eol + "Body line" + eol;
    }

    @Test
    void restores_bound_id_when_authored_id_is_unknown() throws Exception {
        dao.upsert( X, "MyPage", "My Page", "article", null );
        assertEquals( page( X, "\n" ), filter.preSave( ctx, page( Y, "\n" ) ) );
    }

    @Test
    void crlf_page_keeps_crlf_and_every_other_byte() throws Exception {
        dao.upsert( X, "MyPage", "My Page", "article", null );
        assertEquals( page( X, "\r\n" ), filter.preSave( ctx, page( Y, "\r\n" ) ) );
    }

    @Test
    void keeps_authored_id_when_it_is_bound_to_a_different_slug() throws Exception {
        dao.upsert( X, "MyPage", "My Page", "article", null );
        dao.upsert( Y, "OtherPage", "Other", "article", null );
        final String in = page( Y, "\n" );
        assertEquals( in, filter.preSave( ctx, in ) );
    }

    @Test
    void no_op_when_authored_id_equals_bound_id() throws Exception {
        dao.upsert( X, "MyPage", "My Page", "article", null );
        final String in = page( X, "\n" );
        assertEquals( in, filter.preSave( ctx, in ) );
    }

    @Test
    void no_op_when_slug_is_unbound() throws Exception {
        final String in = page( Z, "\n" );
        assertEquals( in, filter.preSave( ctx, in ) );
    }

    /** Deleting a page hard-deletes its row, so a new page on the reused slug keeps the id it authored. */
    @Test
    void deleted_page_slug_reuse_does_not_inherit_the_deleted_id() throws Exception {
        dao.upsert( X, "MyPage", "My Page", "article", null );
        dao.delete( X );
        final String in = page( Z, "\n" );
        assertEquals( in, filter.preSave( ctx, in ) );
    }
}

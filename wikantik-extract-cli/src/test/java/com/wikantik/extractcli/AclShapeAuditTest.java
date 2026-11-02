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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AclShapeAuditTest {

    @Test
    void canonical_rule_is_read_identically_by_all_three() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "Body\n\n[{ALLOW view Admin,Bob}]\n" );
        assertFalse( a.locked() );
        assertEquals( "view:Admin; view:Bob", a.newAcl() );
        assertEquals( a.newAcl(), a.oldTextScan() );
        assertEquals( a.newAcl(), a.oldRender() );
        assertFalse( a.changed() );
    }

    @Test
    void page_without_rules_is_unchanged() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "just text" );
        assertEquals( AclShapeAudit.NONE, a.newAcl() );
        assertFalse( a.changed() );
        assertFalse( a.locked() );
    }

    @Test
    void deny_locks_and_the_old_render_path_read_it_as_a_grant() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "[{DENY view Bob}]\n" );
        assertTrue( a.locked() );
        assertEquals( AclShapeAudit.LOCKED, a.newAcl() );
        assertEquals( "view:Bob", a.oldRender() );
        assertEquals( AclShapeAudit.NONE, a.oldTextScan() );
        assertTrue( a.changed() );
        assertFalse( a.reasons().isEmpty() );
    }

    @Test
    void wrapped_rule_is_a_rule_that_only_the_render_path_used_to_read() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "[{ALLOW view Admin,\nBob}]\n" );
        assertFalse( a.locked() );
        assertEquals( "view:Admin; view:Bob", a.newAcl() );
        assertEquals( AclShapeAudit.NONE, a.oldTextScan() );
        assertTrue( a.changed() );
    }

    @Test
    void escaped_directive_is_flagged_and_widens_against_the_old_text_scan() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "\\[{ALLOW view Admin}]\n" );
        assertFalse( a.locked() );
        assertTrue( a.escaped() );
        assertEquals( AclShapeAudit.NONE, a.newAcl() );
        assertEquals( "view:Admin", a.oldTextScan() );
        assertTrue( a.changed() );
    }

    @Test
    void directive_in_a_code_span_is_literal_for_every_reading() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "Write `[{ALLOW view Admin}]` to restrict.\n" );
        assertFalse( a.locked() );
        assertEquals( AclShapeAudit.NONE, a.newAcl() );
        assertFalse( a.changed() );
    }

    @Test
    void frontmatter_directive_locks() {
        final AclShapeAudit.PageAudit a = AclShapeAudit.audit( "P", "---\nsummary: '[{ALLOW view Admin}]'\n---\nBody\n" );
        assertTrue( a.locked() );
        assertTrue( a.reasons().get( 0 ).contains( "summary" ) );
    }

    @Test
    void acl_ignores_unknown_permissions_the_way_the_old_parser_dropped_them() {
        assertEquals( AclShapeAudit.NONE, AclShapeAudit.acl( List.of( "ALLOW viewbob Admin" ) ) );
        assertEquals( "edit:Jane Doe; view:Jane Doe", AclShapeAudit.acl( List.of( "ALLOW View,edit Jane  Doe" ) ) );
    }
}

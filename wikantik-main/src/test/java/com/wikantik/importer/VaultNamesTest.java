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
package com.wikantik.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.util.WikiPageNameValidator;
import org.junit.jupiter.api.Test;

class VaultNamesTest {

    @Test
    void legalNames() {
        assertEquals( "Beta (v2) 1", VaultNames.legalPageName( "Beta (v2) #1" ) );
        assertEquals( "What is this", VaultNames.legalPageName( "What is this?" ) );
        assertEquals( "a b", VaultNames.legalPageName( "a..b" ) );
        assertEquals( "Untitled", VaultNames.legalPageName( "🎉" ) );
        assertEquals( 128, VaultNames.legalPageName( "x".repeat( 200 ) ).length() );
        assertTrue( WikiPageNameValidator.isValid( VaultNames.legalPageName( "a/b\\c\u0001" ) ) );
    }

    @Test
    void slugs() {
        assertEquals( "my-projects", VaultNames.slug( "My Projects!" ) );
        assertEquals( "", VaultNames.slug( "日本" ) );
        assertTrue( "a-b".matches( FrontmatterSchema.CLUSTER_SLUG_PATTERN ) );
    }

    @Test
    void attachmentNamesReplaceLinkBreakingCharacters() {
        assertEquals( "a-b-c-d-e-f-g.png", VaultNames.attachmentName( "a#b|c[d]e^f\\g.png" ) );
        assertEquals( "x-y.png", VaultNames.attachmentName( "x\u0001y.png" ) );
    }
}

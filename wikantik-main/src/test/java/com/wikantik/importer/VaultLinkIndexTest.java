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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VaultLinkIndexTest {

    @Test
    void resolveExactPathThenBasenameThenShortest() {
        final VaultLinkIndex ix = new VaultLinkIndex(
            List.of( "Projects/Alpha.md", "Archive/Old/Alpha.md", "Beta.md" ), List.of( "Projects/assets/d.png" ) );
        assertEquals( Optional.of( "Archive/Old/Alpha.md" ), ix.resolveNote( "Archive/Old/Alpha" ) );
        assertEquals( Optional.of( "Projects/Alpha.md" ), ix.resolveNote( "alpha" ) );
        assertEquals( Optional.of( "Archive/Old/Alpha.md" ), ix.resolveNote( "Old/Alpha.md" ) );
        assertEquals( Optional.of( "Projects/assets/d.png" ), ix.resolveFile( "D.PNG" ) );
        assertEquals( Optional.empty(), ix.resolveNote( "Gamma" ) );
        assertEquals( Optional.of( "Beta.md" ), ix.resolveNote( "/Beta" ) );
    }

    @Test
    void ambiguousBasenameResolvesDeterministically() {
        final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/ideas.md", "Projects/Ideas.md" ), List.of() );
        assertEquals( Optional.of( "Projects/Ideas.md" ), ix.resolveNote( "ideas" ) );
        assertEquals( Optional.of( "Projects/Ideas.md" ), ix.resolveNote( "Projects/ideas" ) );
    }

    @Test
    void relativeMarkdownLinks() {
        final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/Alpha.md", "Notes/Code.md" ), List.of() );
        assertEquals( Optional.of( "Projects/Alpha.md" ), ix.resolveNoteRelative( "../Projects/Alpha.md", "Notes/Code.md" ) );
        assertEquals( Optional.empty(), ix.resolveNoteRelative( "../../x.md", "Notes/Code.md" ) );
        assertEquals( Optional.of( "Notes/Code.md" ), ix.resolveNoteRelative( "./Code.md", "Notes/Other.md" ) );
    }

    @Test
    void relativeFallsBackToVaultWide() {
        final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/Alpha.md", "Notes/Code.md" ),
            List.of( "img/pic.png" ) );
        assertEquals( Optional.of( "Projects/Alpha.md" ), ix.resolveNoteRelative( "Alpha.md", "Notes/Code.md" ) );
        assertEquals( Optional.of( "img/pic.png" ), ix.resolveFileRelative( "../img/pic.png", "Notes/Code.md" ) );
        assertEquals( Optional.of( "img/pic.png" ), ix.resolveFileRelative( "pic.png", "Notes/Code.md" ) );
    }
}

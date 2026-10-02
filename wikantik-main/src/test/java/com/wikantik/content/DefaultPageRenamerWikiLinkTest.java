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
package com.wikantik.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultPageRenamerWikiLinkTest {

    @Test
    void rewritesEveryNativeFormPreservingTheRest() {
        final String in = "[[OldPage]] [[OldPage#H|a]] [[OldPage\\|t]] ![[OldPage]] ![[OldPage#H]] ![[OldPage/f.png|300]] [[Other]]";
        assertEquals( "[[NewPage]] [[NewPage#H|a]] [[NewPage\\|t]] ![[NewPage]] ![[NewPage#H]] ![[NewPage/f.png|300]] [[Other]]",
                DefaultPageRenamer.rewriteWikiLinks( in, "OldPage", "NewPage" ) );
    }

    @Test
    void rewritesCaseInsensitiveTargetsButNotAliases() {
        assertEquals( "[[NewPage]] [[NewPage|x]] [[Legacy Alias]]",
                DefaultPageRenamer.rewriteWikiLinks( "[[oldpage]] [[old page|x]] [[Legacy Alias]]", "OldPage", "NewPage" ) );
    }

    @Test
    void leavesCodeFencesAndInlineCodeAlone() {
        final String in = "`[[OldPage]]`\n```\n[[OldPage]]\n```\n";
        assertEquals( in, DefaultPageRenamer.rewriteWikiLinks( in, "OldPage", "NewPage" ) );
    }

    @Test
    void leavesSamePageLinksAlone() {
        final String in = "[[#OldPage]]";
        assertEquals( in, DefaultPageRenamer.rewriteWikiLinks( in, "OldPage", "NewPage" ) );
    }
}

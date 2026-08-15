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
package com.wikantik.ui;

import com.wikantik.api.core.Command;
import com.wikantik.api.core.ContextEnum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GenericCommand#toString()} and the {@link ContextEnum} field-order invariant.
 */
class GenericCommandTest {

    @Test
    void toStringRendersTargetExactlyOnce() {
        final Command cmd = GenericCommand.WIKI_FIND.targetedCommand( "MyWiki" );

        final String s = cmd.toString();

        assertTrue( s.endsWith( ",target=MyWiki]" ),
                "toString should render the target once, was: " + s );
    }

    @Test
    void toStringOmitsTargetWhenUntargeted() {
        final String s = GenericCommand.WIKI_FIND.toString();

        assertFalse( s.contains( "target=" ),
                "untargeted command should have no target segment, was: " + s );
    }

    /**
     * Guards the {@link ContextEnum} constructor arguments against transposition. A URL pattern is always a
     * substitution template rooted at the webapp base ({@code %u}); a request context never is. Swapping the two
     * arguments of any constant therefore breaks both halves of this invariant at once.
     */
    @Test
    void noContextEnumConstantHasTransposedConstructorArguments() {
        for ( final ContextEnum ctx : ContextEnum.values() ) {
            assertTrue( ctx.getUrlPattern().startsWith( "%u" ),
                    () -> ctx.name() + ": urlPattern must start with %u, was: " + ctx.getUrlPattern() );
            assertFalse( ctx.getRequestContext().startsWith( "%u" ),
                    () -> ctx.name() + ": requestContext must not be a URL pattern, was: " + ctx.getRequestContext() );
        }
    }
}

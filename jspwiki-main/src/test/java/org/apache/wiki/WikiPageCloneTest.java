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
package org.apache.wiki;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.apache.wiki.TestEngine.with;

/**
 * Tests for WikiPage.clone() proper implementation.
 */
class WikiPageCloneTest {

    static final TestEngine engine = TestEngine.build( with( "jspwiki.cache.enable", "false" ) );

    @Test
    void cloneReturnsSameClass() {
        final WikiPage original = new WikiPage( engine, "TestPage" );
        final WikiPage cloned = original.clone();
        Assertions.assertEquals( original.getClass(), cloned.getClass(),
                "clone() should return an instance of the same class" );
    }

    @Test
    void cloneIsNotSameInstance() {
        final WikiPage original = new WikiPage( engine, "TestPage" );
        final WikiPage cloned = original.clone();
        Assertions.assertNotSame( original, cloned );
    }

    @Test
    void cloneCopiesFields() {
        final WikiPage original = new WikiPage( engine, "TestPage" );
        original.setAuthor( "TestAuthor" );
        original.setVersion( 5 );
        original.setLastModified( new Date() );
        original.setSize( 1234 );
        original.setAttribute( "key", "value" );

        final WikiPage cloned = original.clone();

        Assertions.assertEquals( original.getName(), cloned.getName() );
        Assertions.assertEquals( original.getAuthor(), cloned.getAuthor() );
        Assertions.assertEquals( original.getVersion(), cloned.getVersion() );
        Assertions.assertEquals( original.getLastModified(), cloned.getLastModified() );
        Assertions.assertEquals( original.getSize(), cloned.getSize() );
        Assertions.assertEquals( "value", cloned.getAttribute( "key" ) );
    }

    @Test
    void cloneHasIndependentAttributes() {
        final WikiPage original = new WikiPage( engine, "TestPage" );
        original.setAttribute( "key", "original" );

        final WikiPage cloned = original.clone();
        cloned.setAttribute( "key", "modified" );

        Assertions.assertEquals( "original", original.getAttribute( "key" ),
                "Modifying clone's attributes must not affect the original" );
    }

    @Test
    void cloneHasIndependentDate() {
        final WikiPage original = new WikiPage( engine, "TestPage" );
        final Date date = new Date();
        original.setLastModified( date );

        final WikiPage cloned = original.clone();
        Assertions.assertEquals( date, cloned.getLastModified() );
        Assertions.assertNotSame( date, cloned.getLastModified(),
                "clone() should deep-copy the Date" );
    }
}

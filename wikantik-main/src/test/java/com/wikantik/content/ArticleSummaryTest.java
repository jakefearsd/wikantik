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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Unit tests for {@link ArticleSummary}.
 */
public class ArticleSummaryTest {

    @Test
    public void testBuilderBasic() {
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .title( "Test Page Title" )
            .author( "JohnDoe" )
            .build();

        Assertions.assertEquals( "TestPage", summary.getName() );
        Assertions.assertEquals( "Test Page Title", summary.getTitle() );
        Assertions.assertEquals( "JohnDoe", summary.getAuthor() );
    }

    @Test
    public void testBuilderComplete() {
        final Date now = new Date();

        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .title( "My Test Page" )
            .author( "admin" )
            .lastModified( now )
            .excerpt( "This is an excerpt from the page..." )
            .changeNote( "Updated formatting" )
            .version( 5 )
            .url( "/wiki/TestPage" )
            .size( 1024 )
            .build();

        Assertions.assertEquals( "TestPage", summary.getName() );
        Assertions.assertEquals( "My Test Page", summary.getTitle() );
        Assertions.assertEquals( "admin", summary.getAuthor() );
        Assertions.assertEquals( now, summary.getLastModified() );
        Assertions.assertEquals( "This is an excerpt from the page...", summary.getExcerpt() );
        Assertions.assertEquals( "Updated formatting", summary.getChangeNote() );
        Assertions.assertEquals( 5, summary.getVersion() );
        Assertions.assertEquals( "/wiki/TestPage", summary.getUrl() );
        Assertions.assertEquals( 1024, summary.getSize() );
    }

    @Test
    public void testBuilderRequiresName() {
        final ArticleSummary.Builder builder = new ArticleSummary.Builder();

        Assertions.assertThrows( IllegalStateException.class, builder::build );
    }

    @Test
    public void testBuilderEmptyNameNotAllowed() {
        final ArticleSummary.Builder builder = new ArticleSummary.Builder()
            .name( "" );

        Assertions.assertThrows( IllegalStateException.class, builder::build );
    }

    @Test
    public void testDefaultTitleFromName() {
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .build();

        // When title is not set, it defaults to name
        Assertions.assertEquals( "TestPage", summary.getTitle() );
    }

    @Test
    public void testDefaultUrl() {
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .build();

        // When URL is not set, it defaults to empty string
        Assertions.assertEquals( "", summary.getUrl() );
    }

    @Test
    public void testImmutability() {
        final Date originalDate = new Date();
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( originalDate )
            .build();

        // Modifying the original date should not affect the summary
        final Date retrievedDate = summary.getLastModified();
        originalDate.setTime( 0 );

        Assertions.assertNotEquals( 0, retrievedDate.getTime() );
    }

    @Test
    public void testLastModifiedDefensiveCopy() {
        final Date originalDate = new Date();
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( originalDate )
            .build();

        // Modifying the retrieved date should not affect the summary
        final Date retrieved1 = summary.getLastModified();
        retrieved1.setTime( 0 );

        final Date retrieved2 = summary.getLastModified();
        Assertions.assertNotEquals( 0, retrieved2.getTime() );
    }

    @Test
    public void testEquality() {
        final Date date = new Date();

        final ArticleSummary summary1 = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( date )
            .version( 1 )
            .build();

        final ArticleSummary summary2 = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( date )
            .version( 1 )
            .build();

        final ArticleSummary summary3 = new ArticleSummary.Builder()
            .name( "OtherPage" )
            .lastModified( date )
            .version( 1 )
            .build();

        Assertions.assertEquals( summary1, summary2 );
        Assertions.assertEquals( summary1.hashCode(), summary2.hashCode() );
        Assertions.assertNotEquals( summary1, summary3 );
    }

    @Test
    public void testEqualityWithDifferentVersions() {
        final Date date = new Date();

        final ArticleSummary summary1 = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( date )
            .version( 1 )
            .build();

        final ArticleSummary summary2 = new ArticleSummary.Builder()
            .name( "TestPage" )
            .lastModified( date )
            .version( 2 )
            .build();

        Assertions.assertNotEquals( summary1, summary2 );
    }

    @Test
    public void testToString() {
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .title( "Test Title" )
            .author( "admin" )
            .version( 3 )
            .build();

        final String str = summary.toString();
        Assertions.assertTrue( str.contains( "TestPage" ) );
        Assertions.assertTrue( str.contains( "Test Title" ) );
        Assertions.assertTrue( str.contains( "admin" ) );
        Assertions.assertTrue( str.contains( "3" ) );
    }

    @Test
    public void testNullValues() {
        final ArticleSummary summary = new ArticleSummary.Builder()
            .name( "TestPage" )
            .author( null )
            .excerpt( null )
            .changeNote( null )
            .lastModified( null )
            .build();

        Assertions.assertNull( summary.getAuthor() );
        Assertions.assertNull( summary.getExcerpt() );
        Assertions.assertNull( summary.getChangeNote() );
        Assertions.assertNull( summary.getLastModified() );
    }

    @Test
    public void testBuilderChaining() {
        final ArticleSummary.Builder builder = new ArticleSummary.Builder();

        // Ensure all builder methods return the builder instance
        Assertions.assertSame( builder, builder.name( "Test" ) );
        Assertions.assertSame( builder, builder.title( "Title" ) );
        Assertions.assertSame( builder, builder.author( "Author" ) );
        Assertions.assertSame( builder, builder.lastModified( new Date() ) );
        Assertions.assertSame( builder, builder.excerpt( "Excerpt" ) );
        Assertions.assertSame( builder, builder.changeNote( "Note" ) );
        Assertions.assertSame( builder, builder.version( 1 ) );
        Assertions.assertSame( builder, builder.url( "/url" ) );
        Assertions.assertSame( builder, builder.size( 100 ) );
    }
}

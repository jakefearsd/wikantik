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
package org.apache.wiki.content;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecentArticlesQuery}.
 */
public class RecentArticlesQueryTest {

    @Test
    public void testDefaultValues() {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        Assertions.assertEquals( RecentArticlesQuery.DEFAULT_COUNT, query.getCount() );
        Assertions.assertEquals( RecentArticlesQuery.DEFAULT_SINCE_DAYS, query.getSinceDays() );
        Assertions.assertEquals( RecentArticlesQuery.DEFAULT_EXCERPT_LENGTH, query.getExcerptLength() );
        Assertions.assertTrue( query.isIncludeExcerpt() );
        Assertions.assertNull( query.getExcludePattern() );
        Assertions.assertNull( query.getIncludePattern() );
    }

    @Test
    public void testFluentBuilder() {
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 20 )
            .sinceDays( 7 )
            .includeExcerpt( false )
            .excerptLength( 100 )
            .excludePattern( "System.*" )
            .includePattern( "Blog.*" );

        Assertions.assertEquals( 20, query.getCount() );
        Assertions.assertEquals( 7, query.getSinceDays() );
        Assertions.assertFalse( query.isIncludeExcerpt() );
        Assertions.assertEquals( 100, query.getExcerptLength() );
        Assertions.assertEquals( "System.*", query.getExcludePattern() );
        Assertions.assertEquals( "Blog.*", query.getIncludePattern() );
    }

    @Test
    public void testCountValidation() {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        Assertions.assertThrows( IllegalArgumentException.class, () -> query.count( 0 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.count( -1 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.count( -100 ) );

        // Should not throw for positive values
        Assertions.assertDoesNotThrow( () -> query.count( 1 ) );
        Assertions.assertDoesNotThrow( () -> query.count( 100 ) );
        Assertions.assertDoesNotThrow( () -> query.count( 1000 ) );
    }

    @Test
    public void testSinceDaysValidation() {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        Assertions.assertThrows( IllegalArgumentException.class, () -> query.sinceDays( 0 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.sinceDays( -1 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.sinceDays( -365 ) );

        // Should not throw for positive values
        Assertions.assertDoesNotThrow( () -> query.sinceDays( 1 ) );
        Assertions.assertDoesNotThrow( () -> query.sinceDays( 30 ) );
        Assertions.assertDoesNotThrow( () -> query.sinceDays( 365 ) );
    }

    @Test
    public void testExcerptLengthValidation() {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        Assertions.assertThrows( IllegalArgumentException.class, () -> query.excerptLength( 0 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.excerptLength( -1 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> query.excerptLength( -200 ) );

        // Should not throw for positive values
        Assertions.assertDoesNotThrow( () -> query.excerptLength( 1 ) );
        Assertions.assertDoesNotThrow( () -> query.excerptLength( 200 ) );
        Assertions.assertDoesNotThrow( () -> query.excerptLength( 1000 ) );
    }

    @Test
    public void testEquality() {
        final RecentArticlesQuery query1 = new RecentArticlesQuery()
            .count( 10 )
            .sinceDays( 30 )
            .includeExcerpt( true )
            .excerptLength( 200 );

        final RecentArticlesQuery query2 = new RecentArticlesQuery()
            .count( 10 )
            .sinceDays( 30 )
            .includeExcerpt( true )
            .excerptLength( 200 );

        final RecentArticlesQuery query3 = new RecentArticlesQuery()
            .count( 20 )
            .sinceDays( 30 );

        Assertions.assertEquals( query1, query2 );
        Assertions.assertEquals( query1.hashCode(), query2.hashCode() );
        Assertions.assertNotEquals( query1, query3 );
    }

    @Test
    public void testEqualityWithPatterns() {
        final RecentArticlesQuery query1 = new RecentArticlesQuery()
            .excludePattern( "System.*" )
            .includePattern( "Blog.*" );

        final RecentArticlesQuery query2 = new RecentArticlesQuery()
            .excludePattern( "System.*" )
            .includePattern( "Blog.*" );

        final RecentArticlesQuery query3 = new RecentArticlesQuery()
            .excludePattern( "Admin.*" )
            .includePattern( "Blog.*" );

        Assertions.assertEquals( query1, query2 );
        Assertions.assertNotEquals( query1, query3 );
    }

    @Test
    public void testToString() {
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 5 )
            .sinceDays( 7 );

        final String str = query.toString();
        Assertions.assertTrue( str.contains( "count=5" ) );
        Assertions.assertTrue( str.contains( "sinceDays=7" ) );
    }

    @Test
    public void testChainableSetters() {
        // Ensure all setters return 'this' for chaining
        final RecentArticlesQuery query = new RecentArticlesQuery();

        Assertions.assertSame( query, query.count( 10 ) );
        Assertions.assertSame( query, query.sinceDays( 7 ) );
        Assertions.assertSame( query, query.includeExcerpt( true ) );
        Assertions.assertSame( query, query.excerptLength( 150 ) );
        Assertions.assertSame( query, query.excludePattern( "test" ) );
        Assertions.assertSame( query, query.includePattern( "test" ) );
    }

    @Test
    public void testNullPatterns() {
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .excludePattern( "System.*" )
            .includePattern( "Blog.*" );

        // Should be able to clear patterns by setting null
        query.excludePattern( null );
        query.includePattern( null );

        Assertions.assertNull( query.getExcludePattern() );
        Assertions.assertNull( query.getIncludePattern() );
    }
}

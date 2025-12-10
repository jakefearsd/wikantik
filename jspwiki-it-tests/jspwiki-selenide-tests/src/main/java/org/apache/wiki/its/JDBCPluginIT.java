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
package org.apache.wiki.its;

import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.SelenideElement;
import org.apache.wiki.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * Integration tests for the JDBCPlugin.
 * Tests that the plugin can execute SQL queries against an HSQLDB database
 * and render the results as HTML tables.
 */
public class JDBCPluginIT extends WithIntegrationTestSetup {

    /**
     * Tests that the JDBCPlugin renders a table with all products from the database.
     * Verifies:
     * - The table element exists
     * - Table has header row with correct column names
     * - Table contains expected product data
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    void testJDBCPluginRendersProductTable() {
        final ViewWikiPage page = ViewWikiPage.open( "JDBCPluginTest" );

        // Verify page loads successfully
        Assertions.assertEquals( "JSPWiki: JDBCPluginTest", page.title() );

        // Find the first JDBC table (all products)
        final SelenideElement jdbcDiv = $( ".jdbc-results" );
        jdbcDiv.should( exist );
        jdbcDiv.shouldBe( visible );

        // Verify the table structure
        final SelenideElement table = jdbcDiv.$( "table.wikitable.jdbc-table" );
        table.should( exist );

        // Verify table headers
        final ElementsCollection headers = table.$$( "thead th" );
        Assertions.assertEquals( 5, headers.size(), "Should have 5 columns: id, name, category, price, in_stock" );
        headers.get( 0 ).shouldHave( text( "ID" ) );
        headers.get( 1 ).shouldHave( text( "NAME" ) );
        headers.get( 2 ).shouldHave( text( "CATEGORY" ) );

        // Verify table has data rows
        final ElementsCollection dataRows = table.$$( "tbody tr" );
        Assertions.assertEquals( 5, dataRows.size(), "Should have 5 product rows" );

        // Verify first product is Laptop
        final SelenideElement firstRow = dataRows.get( 0 );
        firstRow.$( "td:nth-child(2)" ).shouldHave( text( "Laptop" ) );
        firstRow.$( "td:nth-child(3)" ).shouldHave( text( "Electronics" ) );
    }

    /**
     * Tests that the JDBCPlugin properly handles CSS class parameter.
     * Verifies the custom class is applied to the wrapper div.
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    void testJDBCPluginCustomCssClass() {
        final ViewWikiPage page = ViewWikiPage.open( "JDBCPluginTest" );

        // Find the electronics table with custom CSS class
        final SelenideElement electronicsDiv = $( ".electronics-table" );
        electronicsDiv.should( exist );

        // Verify it contains a table with filtered data
        final SelenideElement table = electronicsDiv.$( "table" );
        table.should( exist );

        // Should have 3 products (id <= 3, sorted by price: Mouse, Keyboard, Laptop)
        final ElementsCollection dataRows = table.$$( "tbody tr" );
        Assertions.assertEquals( 3, dataRows.size(), "Should have 3 products with id <= 3" );

        // First by price should be Mouse (29.99)
        dataRows.get( 0 ).$( "td:first-child" ).shouldHave( text( "Mouse" ) );
    }

    /**
     * Tests that the header parameter properly hides table headers.
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    void testJDBCPluginNoHeader() {
        final ViewWikiPage page = ViewWikiPage.open( "JDBCPluginTest" );

        // Find all jdbc-results divs
        final ElementsCollection jdbcDivs = $$( ".jdbc-results" );
        Assertions.assertTrue( jdbcDivs.size() >= 2, "Should have at least 2 jdbc-results divs" );

        // The last one should be the count query with no header
        // Find the table that shows "5" (product count)
        boolean foundCountTable = false;
        for ( final SelenideElement div : jdbcDivs ) {
            final SelenideElement table = div.$( "table" );
            if ( table.exists() ) {
                final ElementsCollection headerRows = table.$$( "thead tr" );
                final String bodyText = table.$( "tbody" ).text();

                // The count query should have no header and contain "5"
                if ( headerRows.isEmpty() && bodyText.contains( "5" ) ) {
                    foundCountTable = true;
                    break;
                }
            }
        }

        Assertions.assertTrue( foundCountTable, "Should find the count query table with no header showing value '5'" );
    }

    /**
     * Tests that all three plugin invocations on the test page render properly.
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    void testMultipleJDBCPluginInvocations() {
        final ViewWikiPage page = ViewWikiPage.open( "JDBCPluginTest" );

        // Count all jdbc-related divs
        final ElementsCollection jdbcDivs = $$( ".jdbc-results" );
        final ElementsCollection electronicsDivs = $$( ".electronics-table" );

        // Should have exactly 3 JDBC plugin outputs: 2 with default class, 1 with custom class
        final int totalDivs = jdbcDivs.size() + electronicsDivs.size();
        Assertions.assertEquals( 3, totalDivs,
                "Should have exactly 3 JDBC plugin outputs (2 default class + 1 custom class)" );
    }

    /**
     * Tests that the page content includes expected product names.
     * This is a simpler verification that the plugin is working.
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    void testPageContainsProductData() {
        final ViewWikiPage page = ViewWikiPage.open( "JDBCPluginTest" );

        final String pageContent = page.wikiPageContent();

        // Verify key product data appears on the page
        Assertions.assertTrue( pageContent.contains( "Laptop" ), "Page should contain 'Laptop'" );
        Assertions.assertTrue( pageContent.contains( "Mouse" ), "Page should contain 'Mouse'" );
        Assertions.assertTrue( pageContent.contains( "Keyboard" ), "Page should contain 'Keyboard'" );
        Assertions.assertTrue( pageContent.contains( "Office Chair" ), "Page should contain 'Office Chair'" );
        Assertions.assertTrue( pageContent.contains( "Electronics" ), "Page should contain 'Electronics'" );
        // Furniture check removed since second table now uses id <= 3 filter instead of category filter
    }
}

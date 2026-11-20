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

import java.util.Date;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleTemplateRendererTest {

    private static final Function< String, String > WRAP = text -> "<r>" + text + "</r>";

    private static ArticleSummary article( final String name, final String title ) {
        return new ArticleSummary.Builder().name( name ).title( title ).url( "/wiki/" + name )
                .lastModified( new Date( 0L ) ).build();
    }

    private final List< ArticleSummary > articles = List.of( article( "One", "First" ), article( "Two", "Second" ) );

    @Test
    void iterativeTemplateRepeatsTheArticleBlock() {
        final String out = new ArticleTemplateRenderer( WRAP ).render(
                "N=%%ARTICLE_COUNT%%\n%%ARTICLE_START%%#%%INDEX%% %%NAME%%/%%TITLE%%/%%AUTHOR%%;%%ARTICLE_END%%end", articles );
        assertEquals( "<r>N=2\n#1 One/First/Anonymous;#2 Two/Second/Anonymous;end</r>", out );
    }

    @Test
    void simpleTemplateSubstitutesCountAndList() {
        final String out = new ArticleTemplateRenderer( WRAP ).render( "%%ARTICLE_COUNT%%:\n%%ARTICLE_LIST%%", articles );
        assertEquals( "<r>2:\n* [First|One]\n* [Second|Two]\n</r>", out );
    }

    @Test
    void misorderedMarkersFallBackToTheDefaultHtml() {
        final String out = new ArticleTemplateRenderer( WRAP ).render( "%%ARTICLE_END%%x%%ARTICLE_START%%", articles );
        assertTrue( out.startsWith( "<div class=\"recent-articles\">" ), out );
    }

    @Test
    void renderFailureFallsBackToTheMarkup() {
        final String out = new ArticleTemplateRenderer( t -> { throw new IllegalStateException( "boom" ); } )
                .render( "%%ARTICLE_COUNT%% items", articles );
        assertEquals( "2 items", out );
    }

    @Test
    void withoutAConverterTheMarkupIsReturnedAsIs() {
        assertEquals( "2 items", new ArticleTemplateRenderer( null ).render( "%%ARTICLE_COUNT%% items", articles ) );
    }

    @Test
    void defaultHtmlForNoArticles() {
        assertEquals( "<p>No recent articles found.</p>", new ArticleTemplateRenderer( null ).renderDefaultHtml( List.of() ) );
    }

    @Test
    void defaultHtmlEscapesAndListsEachArticle() {
        final String out = new ArticleTemplateRenderer( null ).renderDefaultHtml(
                List.of( new ArticleSummary.Builder().name( "A" ).title( "T<1>" ).url( "/wiki/A" ).author( "me" ).build() ) );
        assertTrue( out.contains( "<h3><a href=\"/wiki/A\">T&lt;1&gt;</a></h3>" ), out );
        assertTrue( out.contains( "<p class=\"article-meta\">me</p>" ), out );
    }
}

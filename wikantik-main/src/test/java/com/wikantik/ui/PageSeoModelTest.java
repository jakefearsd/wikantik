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

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link PageSeoModel#from}: the fallback chains and
 * derivations that {@link SemanticHeadRenderer} used to compute inline, now
 * isolated from HTML/JSON-LD emission.
 */
class PageSeoModelTest {

    private static final String BASE_URL = "http://example.com";
    private static final String APP_NAME = "Wikantik";

    // ---- effectiveDescription: summary > description > generic fallback ----

    @Test
    void summaryWinsOverDescription() {
        final String body = "---\nsummary: the summary\ndescription: the description\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertEquals( "the summary", model.effectiveDescription() );
    }

    @Test
    void descriptionUsedWhenNoSummary() {
        final String body = "---\ndescription: the description\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertEquals( "the description", model.effectiveDescription() );
    }

    @Test
    void genericFallbackWhenNeitherPresent() {
        final PageSeoModel model = PageSeoModel.from( "PlainPage", "# PlainPage\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "PlainPage - Wikantik wiki page.", model.effectiveDescription() );
    }

    // ---- titleWithApp dedup (exercised via documentTitle) ----

    @Test
    void appNameAppendedWhenNotAlreadyPresent() {
        final String body = "---\ntitle: My Page\n---\n# X\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "MyPage", body, BASE_URL, APP_NAME, null );
        assertEquals( "My Page - Wikantik", model.documentTitle() );
    }

    @Test
    void appNameNotDoubledWhenAlreadyInTitle() {
        final String body = "---\ntitle: Wikantik on Docker\n---\n# X\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "WikantikOnDocker", body, BASE_URL, APP_NAME, null );
        assertEquals( "Wikantik on Docker", model.documentTitle() );
    }

    @Test
    void appNameCaseInsensitiveDedup() {
        final String body = "---\ntitle: hosted on wikantik\n---\n# X\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "Hosted", body, BASE_URL, APP_NAME, null );
        assertEquals( "hosted on wikantik", model.documentTitle(),
                "app name match should be case-insensitive and not append a duplicate" );
    }

    @Test
    void documentTitleFallsBackToPageNameWhenNoFrontmatterTitle() {
        final PageSeoModel model = PageSeoModel.from( "PlainPage", "# PlainPage\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "PlainPage", model.effectiveTitle() );
        assertEquals( "PlainPage - Wikantik", model.documentTitle() );
    }

    // ---- custom vs default image ----

    @Test
    void defaultImageUsedWhenNoFrontmatterImage() {
        final PageSeoModel model = PageSeoModel.from( "PlainPage", "# PlainPage\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertFalse( model.hasCustomImage() );
        assertEquals( BASE_URL + "/og-default.png", model.imageUrl() );
    }

    @Test
    void absoluteFrontmatterImageUsedDirectly() {
        final String body = "---\nimage: https://cdn.example.com/photo.jpg\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertTrue( model.hasCustomImage() );
        assertEquals( "https://cdn.example.com/photo.jpg", model.imageUrl() );
    }

    @Test
    void relativeFrontmatterImagePrefixedWithBaseUrl() {
        final String body = "---\nimage: /uploads/hero.png\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertTrue( model.hasCustomImage() );
        assertEquals( BASE_URL + "/uploads/hero.png", model.imageUrl() );
    }

    // ---- schemaType: upgrade-only via NodeTypeMapping ----

    @Test
    void schemaTypeForHubIsCollectionPage() {
        final PageSeoModel model = PageSeoModel.from( "H", "---\ntype: hub\n---\n# H\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "CollectionPage", model.schemaType() );
        assertTrue( model.isHub() );
    }

    @Test
    void schemaTypeForRunbookIsHowTo() {
        final PageSeoModel model = PageSeoModel.from( "R", "---\ntype: runbook\n---\n# R\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "HowTo", model.schemaType() );
    }

    @Test
    void schemaTypeForDesignIsTechArticle() {
        final PageSeoModel model = PageSeoModel.from( "D", "---\ntype: design\n---\n# D\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "TechArticle", model.schemaType() );
    }

    @Test
    void schemaTypeForUnknownTypeFallsBackToArticleNeverDowngrades() {
        final PageSeoModel model = PageSeoModel.from( "U", "---\ntype: reference\n---\n# U\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "Article", model.schemaType(),
                "unmapped types must fall back to the more-specific Article, never the broader CreativeWork" );
    }

    @Test
    void schemaTypeForMissingTypeIsArticleDefault() {
        final PageSeoModel model = PageSeoModel.from( "PlainPage", "# PlainPage\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( "Article", model.schemaType() );
        assertFalse( model.isHub() );
    }

    // ---- canonical URL shape ----

    @Test
    void canonicalUrlIsBaseUrlPlusWikiSlashPageName() {
        final PageSeoModel model = PageSeoModel.from( "SemanticArticle", "# X\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertEquals( BASE_URL + "/wiki/SemanticArticle", model.canonical() );
    }

    @Test
    void canonicalUrlStripsTrailingSlashFromBaseUrl() {
        final PageSeoModel model = PageSeoModel.from( "X", "# X\n\nBody.\n",
                "http://example.com/", APP_NAME, null );
        assertEquals( "http://example.com", model.safeBaseUrl() );
        assertEquals( "http://example.com/wiki/X", model.canonical() );
    }

    // ---- null-safety of all 5 factory inputs ----

    @Test
    void nullPageNameYieldsEmptySafePageName() {
        final PageSeoModel model = PageSeoModel.from( null, "# X\n\nBody.\n", BASE_URL, APP_NAME, null );
        assertEquals( "", model.safePageName() );
        assertEquals( BASE_URL + "/wiki/", model.canonical() );
    }

    @Test
    void nullRawPageTextTreatedAsEmptyBody() {
        final PageSeoModel model = PageSeoModel.from( "P", null, BASE_URL, APP_NAME, null );
        assertEquals( "P", model.effectiveTitle() );
        assertEquals( "P - Wikantik", model.documentTitle() );
    }

    @Test
    void nullBaseUrlYieldsEmptySafeBaseUrl() {
        final PageSeoModel model = PageSeoModel.from( "P", "# P\n\nBody.\n", null, APP_NAME, null );
        assertEquals( "", model.safeBaseUrl() );
        assertEquals( "/wiki/P", model.canonical() );
        assertEquals( "/og-default.png", model.imageUrl() );
    }

    @Test
    void nullAppNameYieldsEmptySafeAppNameAndNoDedupSuffix() {
        final PageSeoModel model = PageSeoModel.from( "P", "---\ntitle: My Page\n---\n# P\n\nBody.\n",
                BASE_URL, null, null );
        assertEquals( "", model.safeAppName() );
        assertEquals( "My Page", model.documentTitle(),
                "no app name to append means the title is left as-is" );
    }

    @Test
    void nullModifiedIsPreservedAsNull() {
        final PageSeoModel model = PageSeoModel.from( "P", "# P\n\nBody.\n", BASE_URL, APP_NAME, null );
        assertEquals( null, model.modified() );
    }

    @Test
    void nonNullModifiedIsPreserved() throws Exception {
        final SimpleDateFormat fmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
        fmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
        final Date modified = fmt.parse( "2026-04-02T14:05:00Z" );
        final PageSeoModel model = PageSeoModel.from( "P", "# P\n\nBody.\n", BASE_URL, APP_NAME, modified );
        assertEquals( modified, model.modified() );
    }

    // ---- tags / related / keywords / cluster / date ----

    @Test
    void tagsAndKeywordsDerivedFromFrontmatter() {
        final String body = "---\ntags:\n- testing\n- integration\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertEquals( java.util.List.of( "testing", "integration" ), model.tags() );
        assertEquals( "testing, integration", model.effectiveKeywords() );
    }

    @Test
    void noTagsYieldsEmptyKeywords() {
        final PageSeoModel model = PageSeoModel.from( "PlainPage", "# PlainPage\n\nBody.\n",
                BASE_URL, APP_NAME, null );
        assertTrue( model.tags().isEmpty() );
        assertEquals( "", model.effectiveKeywords() );
    }

    @Test
    void clusterAndDateAndCanonicalIdCapturedFromFrontmatter() {
        final String body = "---\ncluster: test-cluster\ndate: 2026-03-20\ncanonical_id: ABC123\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertEquals( "test-cluster", model.cluster() );
        assertEquals( "2026-03-20", model.pageDate() );
        assertEquals( "ABC123", model.canonicalId() );
    }

    @Test
    void metadataMapIsCaptured() {
        final String body = "---\ntitle: My Page\n---\n# P\n\nBody.\n";
        final PageSeoModel model = PageSeoModel.from( "P", body, BASE_URL, APP_NAME, null );
        assertEquals( "My Page", model.metadata().get( "title" ) );
    }
}

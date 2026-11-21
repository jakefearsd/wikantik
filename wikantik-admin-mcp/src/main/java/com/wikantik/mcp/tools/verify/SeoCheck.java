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
package com.wikantik.mcp.tools.verify;

import com.wikantik.api.managers.PageManager;
import com.wikantik.mcp.tools.PageCheck;
import com.wikantik.mcp.tools.PageCheckContext;
import com.wikantik.mcp.tools.PageCheckResult;
import com.wikantik.mcp.tools.PageChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SEO readiness via the shared {@link PageChecks#SEO_CHECKS}. These checks do not use the body. */
public final class SeoCheck implements VerifyCheck {

    private final PageManager pageManager;

    public SeoCheck( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return "seo_readiness";
    }

    @Override
    public void apply( final PageContext page, final Map< String, Object > entry, final VerifyTotals totals ) {
        final PageCheckContext ctx = new PageCheckContext(
                page.pageName(), page.metadata(), "", page.page(), pageManager );
        final List< String > warnings = new ArrayList<>();
        for ( final PageCheck check : PageChecks.SEO_CHECKS ) {
            for ( final PageCheckResult result : check.check( ctx ) ) {
                warnings.add( result.detail() );
            }
        }
        entry.put( "seoWarnings", warnings );
        if ( !warnings.isEmpty() ) {
            totals.seoIssues().add( page.pageName() + ": " + String.join( "; ", warnings ) );
        }
    }

    @Override
    public void summarise( final VerifyTotals totals, final Map< String, Object > summary ) {
        summary.put( "seoIssues", totals.seoIssues() );
    }
}

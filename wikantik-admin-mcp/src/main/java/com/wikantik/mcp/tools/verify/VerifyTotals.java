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

import java.util.ArrayList;
import java.util.List;

/** Aggregates the checks accumulate across all pages, read back when the summary is built. */
public final class VerifyTotals {

    private int brokenLinks;
    private final List< String > pagesWithNoBacklinks = new ArrayList<>();
    private final List< String > metadataIssues = new ArrayList<>();
    private final List< String > seoIssues = new ArrayList<>();
    private final List< String > retrievalIssues = new ArrayList<>();

    public int brokenLinks() {
        return brokenLinks;
    }

    public void addBrokenLinks( final int count ) {
        brokenLinks += count;
    }

    public List< String > pagesWithNoBacklinks() {
        return pagesWithNoBacklinks;
    }

    public List< String > metadataIssues() {
        return metadataIssues;
    }

    public List< String > seoIssues() {
        return seoIssues;
    }

    public List< String > retrievalIssues() {
        return retrievalIssues;
    }
}

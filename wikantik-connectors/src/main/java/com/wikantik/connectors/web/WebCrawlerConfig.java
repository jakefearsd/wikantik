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
package com.wikantik.connectors.web;

import java.util.List;

/**
 * Configuration for a {@link WebCrawlerSourceConnector} BFS crawl.
 *
 * @param seeds         starting URLs, crawled at depth 0
 * @param sameHostOnly  restrict the crawl to the seed's host
 * @param pathPrefix    optional path prefix restriction (e.g. {@code /docs}); {@code null}/blank disables it
 * @param maxPages      stop once this many pages have been emitted
 * @param maxDepth      maximum BFS depth from the seed(s); {@code 0} crawls only the seed(s)
 * @param delayMs       minimum politeness delay between fetches, in milliseconds
 * @param userAgent     User-Agent string sent to the target host and used for robots.txt matching
 * @param respectRobots honor robots.txt disallow rules
 */
public record WebCrawlerConfig(
    List< String > seeds, boolean sameHostOnly, String pathPrefix,
    int maxPages, int maxDepth, long delayMs, String userAgent, boolean respectRobots ) {
}

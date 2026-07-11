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

/** Configuration for a {@link FeedSourceConnector}: seed feed URLs, item cap, whether to fetch the
 *  full article for each entry (default) or emit the feed's inline content, politeness delay, user
 *  agent, and whether to honor robots.txt / restrict article links to the seed feeds' hosts. */
public record FeedConfig(
    List< String > feedUrls, int maxItems, boolean fetchFullArticles, long delayMs,
    String userAgent, boolean respectRobots, boolean sameHostOnly ) {}

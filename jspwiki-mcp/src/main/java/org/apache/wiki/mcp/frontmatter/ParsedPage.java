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
package org.apache.wiki.mcp.frontmatter;

import java.util.Map;

/**
 * Holds the result of parsing a wiki page with optional YAML frontmatter.
 *
 * @param metadata the parsed YAML frontmatter fields (empty map if none)
 * @param body     the page content after the frontmatter block
 */
public record ParsedPage( Map< String, Object > metadata, String body ) {
}

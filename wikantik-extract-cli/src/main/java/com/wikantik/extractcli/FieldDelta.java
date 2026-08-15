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
package com.wikantik.extractcli;

/**
 *  One frontmatter field that differs between the repository and production for a page
 *  present in both.
 *
 *  @param slug   the page carrying the difference
 *  @param field  the frontmatter field name
 *  @param local  the repository value, or {@code null}
 *  @param remote the production value, or {@code null}
 */
public record FieldDelta( String slug, String field, String local, String remote ) {
}

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
package com.wikantik.export;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.wikantik.api.pagegraph.PageDescriptor;

/**
 * The wiki-wide facts {@link ExportSelectionResolver} needs to turn an {@link ExportSelection}
 * into a concrete set of pages, without coupling the resolver to any particular storage or
 * ACL implementation.
 */
public interface ExportCatalog {
    List< PageDescriptor > allPages();

    /** Frontmatter {@code status} of the page, if any. */
    Optional< String > status( String slug );

    /** Outbound page links (attachments and nonexistent pages already excluded). */
    Collection< String > outboundPages( String slug );

    /** Subset of {@code slugs} the caller may view. */
    Set< String > viewable( Collection< String > slugs );
}

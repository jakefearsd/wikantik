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
package com.wikantik.audit;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Decides whether reads of a given page should be audited. Default: NOT audited.
 *  Opt-in via frontmatter {@code audit_reads: true} OR the page's cluster being in
 *  the configured read-audit set. */
public final class AuditReadPolicy {

    private final Function<String, Map<String, Object>> frontmatterByPage;
    private final Function<String, String> clusterByPage;
    private final Set<String> auditedClusters;

    public AuditReadPolicy( final Function<String, Map<String, Object>> frontmatterByPage,
                            final Function<String, String> clusterByPage,
                            final Set<String> auditedClusters ) {
        this.frontmatterByPage = frontmatterByPage;
        this.clusterByPage = clusterByPage;
        this.auditedClusters = auditedClusters;
    }

    public boolean shouldAudit( final String pageName ) {
        final Map<String, Object> fm = frontmatterByPage.apply( pageName );
        if ( fm != null && asBool( fm.get( "audit_reads" ) ) ) return true;
        if ( auditedClusters.isEmpty() ) return false;
        final String cluster = clusterByPage.apply( pageName );
        return cluster != null && auditedClusters.contains( cluster );
    }

    private static boolean asBool( final Object o ) {
        if ( o instanceof Boolean b ) return b;
        if ( o instanceof String s ) return Boolean.parseBoolean( s.trim() );
        return false;
    }
}

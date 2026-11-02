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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders an {@link AclShapeAuditCli} run: the per-page blocks and the summary, as text or as a JSON tree. */
final class AclShapeAuditReport {

    private AclShapeAuditReport() {
    }

    private static List< String > names( final List< AclShapeAudit.PageAudit > audits,
                                          final java.util.function.Predicate< AclShapeAudit.PageAudit > p ) {
        return audits.stream().filter( p ).map( AclShapeAudit.PageAudit::name ).toList();
    }

    static void printText( final List< AclShapeAudit.PageAudit > audits, final List< AclShapeAuditCli.FetchFailure > failures,
                                   final PrintStream out ) {
        for ( final AclShapeAudit.PageAudit a : audits ) {
            out.println( a.name() );
            out.println( "  Reading: " + a.reading() );
            a.reasons().forEach( r -> out.println( "    reason: " + r ) );
            out.println( "  old text-scan ACL: " + a.oldTextScan() );
            out.println( "  old render ACL:    " + a.oldRender() );
            out.println( "  new ACL:           " + a.newAcl() );
            out.println( "  changed: " + ( a.changed() ? "yes" : "no" ) + ( a.escaped() ? "  ESCAPED" : "" ) );
        }
        for ( final AclShapeAuditCli.FetchFailure f : failures ) {
            out.println( f.name() );
            out.println( "  FETCH FAILED: " + f.error() );
        }
        out.println();
        out.println( "== Summary ==" );
        out.println( "pages: " + ( audits.size() + failures.size() ) + "  audited: " + audits.size()
                + "  fetch failures: " + failures.size() );
        summaryList( out, "locked", names( audits, AclShapeAudit.PageAudit::locked ) );
        summaryList( out, "changed", names( audits, AclShapeAudit.PageAudit::changed ) );
        summaryList( out, "ESCAPED", names( audits, AclShapeAudit.PageAudit::escaped ) );
        summaryList( out, "fetch failures", failures.stream().map( AclShapeAuditCli.FetchFailure::name ).toList() );
    }

    private static void summaryList( final PrintStream out, final String label, final List< String > names ) {
        out.println( label + ": " + names.size() );
        names.forEach( n -> out.println( "  " + n ) );
    }

    static Map< String, Object > jsonReport( final List< AclShapeAudit.PageAudit > audits,
                                                      final List< AclShapeAuditCli.FetchFailure > failures ) {
        final List< Map< String, Object > > pages = new ArrayList<>();
        for ( final AclShapeAudit.PageAudit a : audits ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name", a.name() );
            m.put( "reading", a.reading() );
            m.put( "reasons", a.reasons() );
            m.put( "oldTextScanAcl", a.oldTextScan() );
            m.put( "oldRenderAcl", a.oldRender() );
            m.put( "newAcl", a.newAcl() );
            m.put( "changed", a.changed() );
            m.put( "escaped", a.escaped() );
            pages.add( m );
        }
        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "pages", audits.size() + failures.size() );
        summary.put( "audited", audits.size() );
        summary.put( "locked", names( audits, AclShapeAudit.PageAudit::locked ) );
        summary.put( "changed", names( audits, AclShapeAudit.PageAudit::changed ) );
        summary.put( "escaped", names( audits, AclShapeAudit.PageAudit::escaped ) );
        summary.put( "fetchFailures", failures );
        final Map< String, Object > root = new LinkedHashMap<>();
        root.put( "pages", pages );
        root.put( "summary", summary );
        return root;
    }
}

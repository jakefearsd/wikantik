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
package com.wikantik.importer;

import com.wikantik.WikiSubsystems;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;

import java.io.IOException;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/** Plans and applies Obsidian vault imports. */
public final class VaultImportService {

    private final ImportLimits limits;
    private final VaultImportPlanner planner;
    private final WikiSnapshotSource snapshots;
    private final ImportPageSink sink;

    public VaultImportService( final ImportLimits limits, final VaultImportPlanner planner,
                               final WikiSnapshotSource snapshots, final ImportPageSink sink ) {
        this.limits = limits;
        this.planner = planner;
        this.snapshots = snapshots;
        this.sink = sink;
    }

    /** Wires the service from the engine's subsystems (no manager lookups). */
    public static VaultImportService fromSubsystems( final Engine engine, final WikiSubsystems subs ) {
        final Properties props = engine.getWikiProperties();
        final ImportLimits limits = ImportLimits.fromProperties( props );
        final AttachmentGate gate = AttachmentGate.fromProperties( props );
        final VaultImportPlanner planner = new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
            gate, limits.maxPages() );
        final WikiSnapshotSource snapshots = () -> EngineWikiSnapshot.capture( subs.page().pages(),
            subs.core().systemPageRegistry(),
            subs.pageGraph() == null ? null : subs.pageGraph().structuralIndexService() );
        final ImportPageSink sink = new EngineImportPageSink( engine, subs.page().pages(),
            subs.page().attachments(), subs.page().pageSaveHelper(), gate );
        return new VaultImportService( limits, planner, snapshots, sink );
    }

    public ImportLimits limits() {
        return limits;
    }

    /** Reads the spooled zip and produces the dry-run plan. */
    public PlanResult plan( final SpooledUpload upload, final ImportOptions options )
            throws IOException, VaultArchiveException, ImportLimitException, ProviderException {
        final VaultArchive archive = new VaultArchiveReader( limits ).read( upload.file() );
        return planner.plan( archive, options, snapshots.capture(), upload.sha256(), upload.originalName() );
    }

    /** Builds (does not start) the job that applies {@code plan}. */
    public VaultImportJob newJob( final String jobId, final String owner, final String author,
                                  final SpooledUpload upload, final PlanResult plan, final BooleanSupplier permitted ) {
        return new VaultImportJob( jobId, owner, author, upload, plan, sink, permitted );
    }
}

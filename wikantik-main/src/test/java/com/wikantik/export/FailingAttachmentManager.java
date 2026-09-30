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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import com.wikantik.api.attachment.DynamicAttachment;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.providers.AttachmentProvider;

/**
 * Delegating {@link AttachmentManager} test double that throws an {@link IOException} from
 * {@link #getAttachmentStream(Context, Attachment)} for one named file, so {@code ExportService}
 * tests can exercise its per-attachment read-failure fallback (spec §8: warning + skip, the rest
 * of the zip still completes) without needing a genuinely unreadable attachment.
 */
final class FailingAttachmentManager implements AttachmentManager {

    private final AttachmentManager delegate;
    private final String failingFileName;

    FailingAttachmentManager( final AttachmentManager delegate, final String failingFileName ) {
        this.delegate = delegate;
        this.failingFileName = failingFileName;
    }

    @Override
    public InputStream getAttachmentStream( final Context ctx, final Attachment att ) throws ProviderException, IOException {
        if ( att != null && failingFileName.equals( att.getFileName() ) ) {
            throw new IOException( "synthetic getAttachmentStream failure for test" );
        }
        return delegate.getAttachmentStream( ctx, att );
    }

    @Override public boolean attachmentsEnabled() { return delegate.attachmentsEnabled(); }
    @Override public Attachment getAttachmentInfo( final Context context, final String attachmentname, final int version ) throws ProviderException {
        return delegate.getAttachmentInfo( context, attachmentname, version );
    }
    @Override public String getAttachmentInfoName( final Context context, final String attachmentname ) {
        return delegate.getAttachmentInfoName( context, attachmentname );
    }
    @Override public List< Attachment > listAttachments( final Page wikipage ) throws ProviderException { return delegate.listAttachments( wikipage ); }
    @Override public boolean forceDownload( final String name ) { return delegate.forceDownload( name ); }
    @Override public void storeDynamicAttachment( final Context ctx, final DynamicAttachment att ) { delegate.storeDynamicAttachment( ctx, att ); }
    @Override public DynamicAttachment getDynamicAttachment( final String name ) { return delegate.getDynamicAttachment( name ); }
    @Override public void storeAttachment( final Attachment att, final InputStream in ) throws IOException, ProviderException {
        delegate.storeAttachment( att, in );
    }
    @Override public List< Attachment > getVersionHistory( final String attachmentName ) throws ProviderException {
        return delegate.getVersionHistory( attachmentName );
    }
    @Override public Collection< Attachment > getAllAttachments() throws ProviderException { return delegate.getAllAttachments(); }
    @Override public Collection< Attachment > getAllAttachmentsSince( final Date since ) throws ProviderException {
        return delegate.getAllAttachmentsSince( since );
    }
    @Override public AttachmentProvider getCurrentProvider() { return delegate.getCurrentProvider(); }
    @Override public void deleteVersion( final Attachment att ) throws ProviderException { delegate.deleteVersion( att ); }
    @Override public void deleteAttachment( final Attachment att ) throws ProviderException { delegate.deleteAttachment( att ); }
}

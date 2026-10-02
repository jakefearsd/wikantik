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

import java.util.Optional;
import java.util.Properties;

import com.wikantik.api.attachment.AttachmentNameValidator;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.attachment.AttachmentUploadPolicy;

/** Decides whether a vault file may become an attachment (active-content block list + upload policy). */
public final class AttachmentGate {

    private final AttachmentUploadPolicy policy;

    public AttachmentGate( final AttachmentUploadPolicy policy ) {
        this.policy = policy;
    }

    public static AttachmentGate fromProperties( final Properties props ) {
        return new AttachmentGate( AttachmentUploadPolicy.fromProperties( props ) );
    }

    /** The reason the file is refused, or empty when it may be imported. */
    public Optional< String > rejection( final String fileName, final long size ) {
        final String ext = AttachmentNameValidator.getExtension( fileName );
        if ( AttachmentManager.BLOCKED_UPLOAD_EXTENSIONS.contains( ext ) ) {
            return Optional.of( "blocked file type ." + ext );
        }
        if ( !policy.isSizeAllowed( size ) ) {
            return Optional.of( "exceeds " + AttachmentManager.PROP_MAXSIZE + " (" + policy.maxSize() + " bytes)" );
        }
        if ( !policy.isTypeAllowed( fileName ) ) {
            return Optional.of( "file type not allowed by the attachment upload policy" );
        }
        return Optional.empty();
    }
}

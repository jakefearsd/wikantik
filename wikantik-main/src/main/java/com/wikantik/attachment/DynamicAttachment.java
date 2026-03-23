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
package com.wikantik.attachment;

import com.wikantik.api.attachment.DynamicAttachmentProvider;
import com.wikantik.api.core.Engine;


/**
 * Backward-compatibility shim. The canonical interface has moved to
 * {@link com.wikantik.api.attachment.DynamicAttachment} in wikantik-api.
 *
 * <p>This concrete class remains so that existing {@code new DynamicAttachment(...)}
 * call sites continue to compile. New code should program against the
 * {@code com.wikantik.api.attachment.DynamicAttachment} interface instead.
 *
 * @deprecated Use {@link com.wikantik.api.attachment.DynamicAttachment}
 */
@Deprecated
public class DynamicAttachment extends Attachment implements com.wikantik.api.attachment.DynamicAttachment {

    private final DynamicAttachmentProvider provider;

    /**
     *  Creates a DynamicAttachment.
     *
     *  @param engine  The engine which owns this attachment
     *  @param parentPage The page which owns this attachment
     *  @param fileName The filename of the attachment
     *  @param provider The provider which will be used to generate the attachment.
     */
    public DynamicAttachment( final Engine engine,
                              final String parentPage,
                              final String fileName,
                              final DynamicAttachmentProvider provider ) {
        super( engine, parentPage, fileName );
        this.provider = provider;
    }

    /**
     *  Returns the provider which is used to generate this attachment.
     *
     *  @return A Provider component for this attachment.
     */
    @Override
    public DynamicAttachmentProvider getProvider() {
        return provider;
    }

}

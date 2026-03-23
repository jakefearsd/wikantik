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

import com.wikantik.api.exceptions.WikiException;

/**
 * Backward-compatibility shim. The canonical interface has moved to
 * {@link com.wikantik.api.managers.AttachmentManager} in wikantik-api.
 *
 * <p>Existing code that imports {@code com.wikantik.attachment.AttachmentManager}
 * continues to work unchanged. New code should import from
 * {@code com.wikantik.api.managers} instead.
 *
 * @deprecated Use {@link com.wikantik.api.managers.AttachmentManager}
 */
@Deprecated
public interface AttachmentManager extends com.wikantik.api.managers.AttachmentManager {

    /**
     * Re-declaration of the static utility so that call sites using
     * {@code com.wikantik.attachment.AttachmentManager.validateFileName(...)}
     * continue to compile (Java static interface methods are not inherited).
     *
     * @param filename file name to validate.
     * @return A validated name with annoying characters replaced.
     * @throws WikiException If the filename is not legal (e.g. empty)
     */
    static String validateFileName( final String filename ) throws WikiException {
        return com.wikantik.api.managers.AttachmentManager.validateFileName( filename );
    }
}

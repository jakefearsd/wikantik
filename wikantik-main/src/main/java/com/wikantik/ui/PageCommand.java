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
package com.wikantik.ui;

import com.wikantik.api.core.Command;

/**
 * Backward-compatible facade — all constants delegate to {@link GenericCommand}.
 *
 * @deprecated Use {@link GenericCommand} constants directly.
 * @since 2.4.22
 */
@Deprecated
public final class PageCommand {

    public static final Command ATTACH   = GenericCommand.PAGE_ATTACH;
    public static final Command COMMENT  = GenericCommand.PAGE_COMMENT;
    public static final Command CONFLICT = GenericCommand.PAGE_CONFLICT;
    public static final Command DELETE   = GenericCommand.PAGE_DELETE;
    public static final Command DIFF     = GenericCommand.PAGE_DIFF;
    public static final Command EDIT     = GenericCommand.PAGE_EDIT;
    public static final Command INFO     = GenericCommand.PAGE_INFO;
    public static final Command PREVIEW  = GenericCommand.PAGE_PREVIEW;
    public static final Command RENAME   = GenericCommand.PAGE_RENAME;
    public static final Command RSS      = GenericCommand.PAGE_RSS;
    public static final Command UPLOAD   = GenericCommand.PAGE_UPLOAD;
    public static final Command VIEW     = GenericCommand.PAGE_VIEW;
    public static final Command NONE     = GenericCommand.PAGE_NONE;
    public static final Command OTHER    = GenericCommand.PAGE_OTHER;

    private PageCommand() {
    }

}

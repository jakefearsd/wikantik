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
public final class WikiCommand {

    public static final Command ADMIN        = GenericCommand.WIKI_ADMIN;
    public static final Command CREATE_GROUP = GenericCommand.WIKI_CREATE_GROUP;
    public static final Command ERROR        = GenericCommand.WIKI_ERROR;
    public static final Command FIND         = GenericCommand.WIKI_FIND;
    public static final Command INSTALL      = GenericCommand.WIKI_INSTALL;
    public static final Command LOGIN        = GenericCommand.WIKI_LOGIN;
    public static final Command LOGOUT       = GenericCommand.WIKI_LOGOUT;
    public static final Command MESSAGE      = GenericCommand.WIKI_MESSAGE;
    public static final Command PREFS        = GenericCommand.WIKI_PREFS;
    public static final Command WORKFLOW     = GenericCommand.WIKI_WORKFLOW;

    private WikiCommand() {
    }

}

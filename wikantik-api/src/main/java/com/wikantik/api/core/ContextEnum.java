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
package com.wikantik.api.core;


public enum ContextEnum {

    GROUP_DELETE( "deleteGroup", "%udelete-group?group=%n", null ),
    GROUP_EDIT( "editGroup", "%uedit-group?group=%n", "EditGroupContent" ),
    GROUP_VIEW( "viewGroup", "%ugroup?group=%n", "GroupContent" ),

    PAGE_ATTACH( "att", "%uattach/%n", null ),
    PAGE_COMMENT( "comment", "%ucomment?page=%n", "CommentContent" ),
    PAGE_CONFLICT ( "conflict", "%upage-modified?page=%n", "ConflictContent" ),
    PAGE_DELETE( "del", "%udelete?page=%n", null ),
    PAGE_DIFF( "diff", "%udiff/%n", "DiffContent" ),
    PAGE_EDIT( "edit", "%uedit/%n", "EditContent" ),
    PAGE_INFO( "info", "%uinfo?page=%n", "InfoContent" ),
    PAGE_NONE( "", "%u%n", null ),
    PAGE_PREVIEW( "preview", "%upreview?page=%n", "PreviewContent" ),
    PAGE_RENAME( "rename", "%urename?page=%n", "InfoContent" ),
    PAGE_RSS( "rss", "%urss", null ),
    PAGE_UPLOAD( "upload", "%uupload?page=%n", null ),
    PAGE_VIEW( "view", "%uwiki/%n", "PageContent" ),

    REDIRECT( "", "%u%n", null ),

    WIKI_ADMIN( "admin", "%uadmin", "AdminContent" ),
    WIKI_CREATE_GROUP( "createGroup", "%unew-group", "NewGroupContent" ),
    WIKI_ERROR( "error", "%uerror", "DisplayMessage" ),
    WIKI_FIND( "find", "%usearch", "FindContent" ),
    WIKI_INSTALL( "install", "%uinstall", null ),
    WIKI_LOGIN( "login", "%ulogin?redirect=%n", "LoginContent" ),
    WIKI_LOGOUT( "logout", "%ulogout", null ),
    WIKI_MESSAGE( "message", "%umessage", "DisplayMessage" ),
    WIKI_PREFS( "prefs", "%upreferences", "PreferencesContent" ),
    WIKI_WORKFLOW( "workflow", "%uworkflow", "WorkflowContent" );

    private final String contentTemplate;
    private final String requestContext;
    private final String urlPattern;

    ContextEnum( final String requestContext, final String urlPattern, final String contentTemplate ) {
        this.requestContext = requestContext;
        this.urlPattern = urlPattern;
        this.contentTemplate = contentTemplate;
    }

    public String getContentTemplate() {
        return contentTemplate;
    }

    public String getRequestContext() {
        return requestContext;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

}

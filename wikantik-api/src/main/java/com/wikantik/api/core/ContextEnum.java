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

    GROUP_DELETE( "deleteGroup", "%udelete-group?group=%n" ),
    GROUP_EDIT( "editGroup", "%uedit-group?group=%n" ),
    GROUP_VIEW( "viewGroup", "%ugroup?group=%n" ),

    PAGE_ATTACH( "att", "%uattach/%n" ),
    PAGE_COMMENT( "comment", "%ucomment?page=%n" ),
    PAGE_CONFLICT ( "conflict", "%upage-modified?page=%n" ),
    PAGE_DELETE( "del", "%udelete?page=%n" ),
    PAGE_DIFF( "diff", "%udiff/%n" ),
    PAGE_EDIT( "edit", "%uedit/%n" ),
    PAGE_INFO( "info", "%uinfo?page=%n" ),
    PAGE_NONE( "", "%u%n" ),
    PAGE_PREVIEW( "preview", "%upreview?page=%n" ),
    PAGE_RENAME( "rename", "%urename?page=%n" ),
    PAGE_UPLOAD( "upload", "%uupload?page=%n" ),
    PAGE_VIEW( "view", "%uwiki/%n" ),

    REDIRECT( "", "%u%n" ),

    WIKI_ADMIN( "admin", "%uadmin" ),
    WIKI_CREATE_GROUP( "createGroup", "%unew-group" ),
    WIKI_ERROR( "error", "%uerror" ),
    WIKI_FIND( "find", "%usearch" ),
    WIKI_LOGIN( "login", "%ulogin?redirect=%n" ),
    WIKI_LOGOUT( "logout", "%ulogout" ),
    WIKI_PREFS( "prefs", "%upreferences" );

    private final String requestContext;
    private final String urlPattern;

    ContextEnum( final String requestContext, final String urlPattern ) {
        this.requestContext = requestContext;
        this.urlPattern = urlPattern;
    }

    public String getRequestContext() {
        return requestContext;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

}

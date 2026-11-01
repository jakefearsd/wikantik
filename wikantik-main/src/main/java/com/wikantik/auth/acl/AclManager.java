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
package com.wikantik.auth.acl;

import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Page;
import com.wikantik.api.engine.Initializable;
import com.wikantik.auth.WikiSecurityException;

/**
 *  Specifies how to parse and return ACLs from wiki pages.
 *
 *  @since 2.3
 */
public interface AclManager extends Initializable {

    /**
     * Parses one textual access rule into {@code into}. The line is in form
     * "ALLOW <permission> <principal>, <principal>, <principal>". Nothing but {@code into} is changed: in particular the
     * page's own ACL is neither read nor set.
     *
     * @param page     the wiki page the rule belongs to (names the permission's target)
     * @param into     the ACL to add the rule's entries to
     * @param ruleLine the rule line, as described above
     * @return {@code into}
     * @throws WikiSecurityException if the ruleLine was faulty somehow
     * @since 2.1.121
     */
    Acl parseAcl( Page page, Acl into, String ruleLine ) throws WikiSecurityException;

    /**
     * Returns the access control list the wiki enforces for the page: what {@link AccessRules#read} makes of the page's
     * text, for its version. An attachment has its parent page's. The result does not depend on anything else that
     * happened to the page (a render, an ACL some caller set on it) and must not be modified.
     *
     * @param page the wiki page
     * @since 2.2.121
     * @return the Acl representing permissions for the page
     */
    Acl getPermissions( Page page );

    /**
     * Sets the access control list for the page and persists it.
     *
     * @param page the wiki page
     * @param acl the access control list
     * @since 2.5
     * @throws WikiSecurityException if the ACL cannot be set or persisted
     */
    void setPermissions( Page page, Acl acl ) throws WikiSecurityException;

}

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
package com.wikantik.api.attachment;

import com.wikantik.api.core.Attachment;


/**
 *  A DynamicAttachment is an attachment which does not really exist, but is created dynamically by a JSPWiki component.
 *  <p>
 *  Note that a DynamicAttachment might not be available before it is actually created by a component (e.g. plugin), and therefore trying
 *  to access it before that component has been invoked, might result in a surprising 404.
 *  <p>
 *  DynamicAttachments are not listed among regular attachments in the current version.
 *
 *  @since 2.5.34
 */
public interface DynamicAttachment extends Attachment {

    /**
     *  Returns the provider which is used to generate this attachment.
     *
     *  @return A Provider component for this attachment.
     */
    DynamicAttachmentProvider getProvider();

}

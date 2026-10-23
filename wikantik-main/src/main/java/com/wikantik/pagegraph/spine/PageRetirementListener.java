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
package com.wikantik.pagegraph.spine;

/**
 *  Told when a page is truly deleted and its {@code canonical_id} retired, so subsystems that key
 *  rows on that id (page ownership, comment threads, outbound citations) can drop them.
 *
 *  <p>Fired by {@link DefaultStructuralIndexService} once it has removed the page from its
 *  projection and {@code page_canonical_ids} — the one place that knows whether the id still
 *  belongs to a live page. A rename never fires it: the canonical_id is rename-stable, and the
 *  rename re-points the id at the new slug instead. Listeners run after the index's own state
 *  change, under its lock, so they must not block; one listener failing is logged and does not
 *  stop the others.</p>
 */
@FunctionalInterface
public interface PageRetirementListener {

    /**
     *  @param slug        the deleted page's name
     *  @param canonicalId the canonical_id it carried, now retired
     */
    void pageRetired( String slug, String canonicalId );
}

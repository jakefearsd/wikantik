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
package com.wikantik.api.livevalues;

import com.wikantik.api.livevalues.LiveValueExpander.Resolution;

import java.util.Map;
import java.util.Set;

/** A provider of live values, implemented by modules that own the underlying facts. */
public interface LiveValueSource {

    /** Current values, called on recompute; keys are full names such as {@code tools.admin}. */
    Map< String, Resolution > values();

    /** Names this source owns even when {@link #values()} currently returns nothing for them. */
    Set< String > declaredNames();

    /**
     * Thrown by a source (or a collaborator it calls) that cannot compute yet, e.g. during boot. Its names resolve
     * unwired and the revision is not recorded as computed, so the next expansion retries. Any other exception is
     * cached for the revision.
     */
    final class NotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotReadyException( final String message ) {
            super( message );
        }
    }
}

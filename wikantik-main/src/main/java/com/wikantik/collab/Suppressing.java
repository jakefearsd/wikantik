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
package com.wikantik.collab;

/**
 * Runs clean-up work while an original failure is in flight, so a second failure in the clean-up (memory running
 * out while ending the publish, say) is attached to the original as suppressed instead of replacing it.
 */
final class Suppressing {

    private Suppressing() {
    }

    /** Runs {@code cleanup}; anything it throws is added to {@code original} as suppressed and not rethrown. */
    static void run( final Throwable original, final Runnable cleanup ) {
        try {
            cleanup.run();
        } catch( final Throwable second ) {
            original.addSuppressed( second );
        }
    }
}

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
package com.wikantik.work;

import com.wikantik.jdbc.Jdbc;

/** Builds the notification-side stores; every store is stateless (a new holder is built per call). */
final class JdbcNotifyStores {

    private JdbcNotifyStores() {
    }

    static WorkNotifyStores create( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        final JdbcWorkWatches watches = new JdbcWorkWatches( jdbc );
        return new WorkNotifyStores( watches, new JdbcWorkNotifications( jdbc, watches ),
            new JdbcWorkWebhooks( jdbc, catalog ), new JdbcWorkFind( jdbc ),
            new JdbcWorkFilters( jdbc ), new JdbcWorkAttachments( jdbc, catalog ),
            new JdbcWorkCodeHostStore( jdbc, catalog ), new JdbcWorkFlow( jdbc ) );
    }
}

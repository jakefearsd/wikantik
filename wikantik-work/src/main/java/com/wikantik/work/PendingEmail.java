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

import java.time.LocalDate;

/**
 * One unmailed notice with what an email about it needs.
 *
 * @param recipient    the person's login (never printed in a message)
 * @param mode         the person's email choice ({@link EmailMode#EACH} without a stored preference)
 * @param lastDigestOn the day their last daily digest went out; null when none
 * @param notice       the notice, its event and the titles
 * @param commentText  the comment's text; null unless the event is a comment or an edited comment that is not removed
 */
public record PendingEmail( String recipient, EmailMode mode, LocalDate lastDigestOn, NoticeView notice,
                            String commentText ) {
}

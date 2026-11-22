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

import java.util.Objects;

/** The data rules an item must satisfy, shared by creation and patching. Every failure names its field. */
final class ItemRules {

    static final int MAX_TITLE = 200;

    private ItemRules() {
    }

    static void checkHandoffNote( final Item current, final Item next, final String note ) {
        if ( next.status() != current.status() && !Objects.equals( next.assignee(), current.assignee() )
                && ( note == null || note.isBlank() ) ) {
            throw new WorkValidationException( "note", "Changing status and assignee together needs a note." );
        }
    }

    /** Field rules; {@code newParent} is the freshly resolved parent, null when the parent is not being set. */
    static void validate( final Item next, final Item newParent ) {
        validateTitle( next.title() );
        validateShape( next, newParent );
        if ( next.priority() < 1 || next.priority() > 4 ) {
            throw new WorkValidationException( "priority", "Priority must be between 1 and 4." );
        }
    }

    private static void validateTitle( final String title ) {
        if ( title == null || title.isBlank() ) {
            throw new WorkValidationException( "title", "A title is required." );
        }
        if ( title.length() > MAX_TITLE ) {
            throw new WorkValidationException( "title", "A title may be at most " + MAX_TITLE + " characters." );
        }
    }

    /** What an epic may not carry: a product, a type other than feature, an estimate or a sprint. */
    private static void validateEpic( final Item epic ) {
        if ( epic.productId() != null ) {
            throw new WorkValidationException( "productKey", "An epic has no product of its own." );
        }
        if ( epic.type() != WorkType.FEATURE ) {
            throw new WorkValidationException( "type", "An epic is always a feature." );
        }
        if ( epic.estimate() != null ) {
            throw new WorkValidationException( "estimate",
                "An epic has no estimate of its own; it shows the sum of its children." );
        }
        if ( epic.sprintId() != null ) {
            throw new WorkValidationException( "sprint", "An epic is not planned into a sprint." );
        }
    }

    private static void validateShape( final Item next, final Item newParent ) {
        if ( !WorkStatus.allowedFor( next.level() ).contains( next.status() ) ) {
            throw new WorkValidationException( "status",
                "A " + next.level().wire() + " cannot have status " + next.status().wire() + "." );
        }
        if ( next.level() == WorkLevel.EPIC ) {
            validateEpic( next );
        }
        if ( newParent != null && !newParent.level().canParent( next.level() ) ) {
            throw new WorkValidationException( "parentKey",
                "A " + newParent.level().wire() + " cannot be the parent of a " + next.level().wire() + "." );
        }
    }
}

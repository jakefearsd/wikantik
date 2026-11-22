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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/** Value coercion for item changes: JSON-ish types in, domain types out; every failure names its field. */
final class PatchValues {

    private static final double LONG_LIMIT = 0x1p63;
    private static final BigDecimal MAX_ESTIMATE = new BigDecimal( "999.5" );
    private static final BigDecimal TWO = BigDecimal.valueOf( 2 );

    private PatchValues() {
    }

    static String blankToNull( final String s ) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String string( final String field, final Object v ) {
        if ( v == null ) {
            return null;
        }
        if ( v instanceof String s ) {
            return s;
        }
        throw new WorkValidationException( field, field + " must be text." );
    }

    static String requiredString( final String field, final Object v ) {
        final String s = string( field, v );
        if ( s == null ) {
            throw new WorkValidationException( field, field + " cannot be cleared." );
        }
        return s;
    }

    static String bodyOf( final Object v ) {
        final String s = string( "body", v );
        return s == null ? "" : s;
    }

    static double number( final String field, final Object v ) {
        if ( v instanceof Number n && Double.isFinite( n.doubleValue() ) ) {
            return n.doubleValue();
        }
        throw new WorkValidationException( field, field + " must be a number." );
    }

    static int priority( final Object v ) {
        final double d = number( "priority", v );
        if ( d != Math.rint( d ) ) {
            throw new WorkValidationException( "priority", "Priority must be a whole number." );
        }
        return d < Integer.MIN_VALUE || d > Integer.MAX_VALUE ? 0 : (int) d;
    }

    static WorkStatus status( final Object v ) {
        return WorkStatus.fromWire( requiredString( "status", v ) );
    }

    static WorkKind kind( final Object v ) {
        return WorkKind.fromWire( requiredString( "kind", v ) );
    }

    static WorkType type( final Object v ) {
        return WorkType.fromWire( requiredString( "type", v ) );
    }

    static List< String > labels( final Object v ) {
        if ( v == null ) {
            return List.of();
        }
        if ( v instanceof List< ? > list && list.stream().allMatch( String.class::isInstance ) ) {
            return Labels.normalise( list.stream().map( String.class::cast ).toList() );
        }
        throw new WorkValidationException( "labels", "labels must be a list of labels." );
    }

    /** A sprint id: null clears; otherwise a whole positive number. */
    static Long sprintId( final Object v ) {
        if ( v == null ) {
            return null;
        }
        if ( v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte ) {
            if ( ( (Number) v ).longValue() >= 1 ) {
                return ( (Number) v ).longValue();
            }
        } else if ( v instanceof Number n && Double.isFinite( n.doubleValue() )
                && n.doubleValue() == Math.rint( n.doubleValue() ) && n.doubleValue() >= 1
                && n.doubleValue() < LONG_LIMIT ) {
            return n.longValue();
        }
        throw new WorkValidationException( "sprint", "sprint must be a sprint id." );
    }

    /** An estimate in points: null clears; otherwise 0 to 999.5 in halves, at scale one. */
    static BigDecimal estimate( final Object v ) {
        if ( v == null ) {
            return null;
        }
        if ( v instanceof Number n && Double.isFinite( n.doubleValue() ) ) {
            final BigDecimal points = new BigDecimal( n.toString() );
            if ( points.signum() >= 0 && points.compareTo( MAX_ESTIMATE ) <= 0
                    && points.multiply( TWO ).stripTrailingZeros().scale() <= 0 ) {
                return points.setScale( 1, RoundingMode.UNNECESSARY );
            }
        }
        throw new WorkValidationException( "estimate", "An estimate is a number of points from 0 to 999.5, in halves." );
    }

    static Autonomy autonomy( final Object v ) {
        final String s = string( "autonomy", v );
        return s == null ? null : Autonomy.fromWire( s );
    }

    static DoneMeans doneMeans( final Object v ) {
        final String s = string( "doneMeans", v );
        return s == null ? null : DoneMeans.fromWire( s );
    }

    static Boolean allowDirect( final Object v ) {
        if ( v == null || v instanceof Boolean ) {
            return (Boolean) v;
        }
        throw new WorkValidationException( "allowDirect", "allowDirect must be true or false." );
    }

    static LocalDate dueOn( final Object v ) {
        final String s = string( "dueOn", v );
        if ( s == null || s.isBlank() ) {
            return null;
        }
        try {
            return LocalDate.parse( s.trim() );
        } catch ( final DateTimeException e ) {
            throw new WorkValidationException( "dueOn", "dueOn must be an ISO date such as 2026-11-15." );
        }
    }

    static String assignee( final Object v ) {
        return blankToNull( string( "assignee", v ) );
    }
}

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
package com.wikantik.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WikiPrincipal}, verifying construction, equality, hashCode,
 * compareTo, and toString behavior. These contracts are security-critical:
 * broken equality or hashCode can cause authentication and authorization failures.
 */
class WikiPrincipalTest {

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "Construction" )
    class Construction {

        @Test
        @DisplayName( "Single-arg constructor sets name and defaults to UNSPECIFIED type" )
        void singleArgConstructor() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertEquals( "Alice", p.getName() );
            assertEquals( WikiPrincipal.UNSPECIFIED, p.getType() );
        }

        @Test
        @DisplayName( "Two-arg constructor with FULL_NAME type" )
        void twoArgConstructorFullName() {
            final WikiPrincipal p = new WikiPrincipal( "Alice Wonderland", WikiPrincipal.FULL_NAME );
            assertEquals( "Alice Wonderland", p.getName() );
            assertEquals( WikiPrincipal.FULL_NAME, p.getType() );
        }

        @Test
        @DisplayName( "Two-arg constructor with LOGIN_NAME type" )
        void twoArgConstructorLoginName() {
            final WikiPrincipal p = new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME );
            assertEquals( "alice", p.getName() );
            assertEquals( WikiPrincipal.LOGIN_NAME, p.getType() );
        }

        @Test
        @DisplayName( "Two-arg constructor with WIKI_NAME type" )
        void twoArgConstructorWikiName() {
            final WikiPrincipal p = new WikiPrincipal( "AliceWonderland", WikiPrincipal.WIKI_NAME );
            assertEquals( "AliceWonderland", p.getName() );
            assertEquals( WikiPrincipal.WIKI_NAME, p.getType() );
        }

        @Test
        @DisplayName( "Two-arg constructor with UNSPECIFIED type" )
        void twoArgConstructorUnspecified() {
            final WikiPrincipal p = new WikiPrincipal( "Alice", WikiPrincipal.UNSPECIFIED );
            assertEquals( "Alice", p.getName() );
            assertEquals( WikiPrincipal.UNSPECIFIED, p.getType() );
        }

        @Test
        @DisplayName( "Invalid type throws IllegalArgumentException" )
        void invalidTypeThrows() {
            assertThrows( IllegalArgumentException.class, () -> new WikiPrincipal( "Alice", "bogusType" ) );
        }

        @Test
        @DisplayName( "Null type throws IllegalArgumentException" )
        void nullTypeThrows() {
            assertThrows( Exception.class, () -> new WikiPrincipal( "Alice", null ) );
        }

        @Test
        @DisplayName( "Empty-string type throws IllegalArgumentException" )
        void emptyTypeThrows() {
            assertThrows( IllegalArgumentException.class, () -> new WikiPrincipal( "Alice", "" ) );
        }

        @Test
        @DisplayName( "Package-private no-arg constructor sets name to null (serialization support)" )
        void noArgConstructor() {
            // Package-private constructor is accessible from the same package
            final WikiPrincipal p = new WikiPrincipal();
            assertNull( p.getName() );
            assertEquals( WikiPrincipal.UNSPECIFIED, p.getType() );
        }

        @Test
        @DisplayName( "Name with null is allowed by single-arg constructor" )
        void nullNameSingleArg() {
            final WikiPrincipal p = new WikiPrincipal( null );
            assertNull( p.getName() );
        }

        @Test
        @DisplayName( "Empty name is allowed" )
        void emptyName() {
            final WikiPrincipal p = new WikiPrincipal( "" );
            assertEquals( "", p.getName() );
        }

        @Test
        @DisplayName( "Name with whitespace is preserved" )
        void whitespaceNamePreserved() {
            final WikiPrincipal p = new WikiPrincipal( "  Alice  " );
            assertEquals( "  Alice  ", p.getName() );
        }
    }

    // ---------------------------------------------------------------
    // Equality contract
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "Equality contract" )
    class EqualityContract {

        @Test
        @DisplayName( "Reflexive: x.equals(x) is true" )
        void reflexive() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertEquals( p, p );
        }

        @Test
        @DisplayName( "Symmetric: x.equals(y) implies y.equals(x)" )
        void symmetric() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice" );
            assertEquals( p1, p2 );
            assertEquals( p2, p1 );
        }

        @Test
        @DisplayName( "Transitive: x.equals(y) and y.equals(z) implies x.equals(z)" )
        void transitive() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice", WikiPrincipal.LOGIN_NAME );
            final WikiPrincipal p3 = new WikiPrincipal( "Alice", WikiPrincipal.WIKI_NAME );
            assertEquals( p1, p2 );
            assertEquals( p2, p3 );
            assertEquals( p1, p3, "Transitivity violated" );
        }

        @Test
        @DisplayName( "Equality is by name only, not by type -- same name, different types are equal" )
        void equalityIgnoresType() {
            final WikiPrincipal fullName = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal loginName = new WikiPrincipal( "Alice", WikiPrincipal.LOGIN_NAME );
            final WikiPrincipal wikiName = new WikiPrincipal( "Alice", WikiPrincipal.WIKI_NAME );
            final WikiPrincipal unspecified = new WikiPrincipal( "Alice", WikiPrincipal.UNSPECIFIED );

            assertEquals( fullName, loginName );
            assertEquals( fullName, wikiName );
            assertEquals( fullName, unspecified );
            assertEquals( loginName, wikiName );
            assertEquals( loginName, unspecified );
            assertEquals( wikiName, unspecified );
        }

        @Test
        @DisplayName( "Different names are not equal" )
        void differentNames() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Bob" );
            assertNotEquals( p1, p2 );
        }

        @Test
        @DisplayName( "Equality is case-sensitive" )
        void caseSensitive() {
            final WikiPrincipal lower = new WikiPrincipal( "alice" );
            final WikiPrincipal upper = new WikiPrincipal( "Alice" );
            assertNotEquals( lower, upper );
        }

        @Test
        @DisplayName( "Not equal to null" )
        void notEqualToNull() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertNotEquals( null, p );
            //noinspection SimplifiableAssertion
            assertFalse( p.equals( null ) );
        }

        @Test
        @DisplayName( "Not equal to a non-WikiPrincipal Principal" )
        void notEqualToOtherPrincipalType() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            final Principal other = () -> "Alice";
            assertNotEquals( p, other );
        }

        @Test
        @DisplayName( "Not equal to a String with the same name" )
        void notEqualToString() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            //noinspection AssertBetweenInconvertibleTypes
            assertNotEquals( p, "Alice" );
        }

        @Test
        @DisplayName( "Not equal to an arbitrary object" )
        void notEqualToArbitraryObject() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertNotEquals( p, 42 );
        }

        @Test
        @DisplayName( "Consistent: multiple invocations return the same result" )
        void consistent() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice" );
            for( int i = 0; i < 100; i++ ) {
                assertEquals( p1, p2 );
            }
        }

        @Test
        @DisplayName( "Two principals with same name but one via single-arg and other via two-arg are equal" )
        void singleArgVsTwoArgEquality() {
            final WikiPrincipal single = new WikiPrincipal( "Alice" );
            final WikiPrincipal twoArg = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            assertEquals( single, twoArg );
            assertEquals( twoArg, single );
        }
    }

    // ---------------------------------------------------------------
    // hashCode contract
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "hashCode contract" )
    class HashCodeContract {

        @Test
        @DisplayName( "hashCode equals name's hashCode" )
        void hashCodeMatchesNameHashCode() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertEquals( "Alice".hashCode(), p.hashCode() );
        }

        @Test
        @DisplayName( "Equal objects have the same hashCode" )
        void equalObjectsSameHashCode() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice", WikiPrincipal.LOGIN_NAME );
            assertEquals( p1, p2, "Precondition: principals must be equal" );
            assertEquals( p1.hashCode(), p2.hashCode() );
        }

        @Test
        @DisplayName( "Different names generally produce different hashCodes" )
        void differentNamesGenerallyDifferentHashCodes() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Bob" );
            // Not strictly required by the contract, but validates the implementation
            assertNotEquals( p1.hashCode(), p2.hashCode() );
        }

        @Test
        @DisplayName( "hashCode is consistent across multiple invocations" )
        void hashCodeConsistent() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            final int first = p.hashCode();
            for( int i = 0; i < 100; i++ ) {
                assertEquals( first, p.hashCode() );
            }
        }

        @Test
        @DisplayName( "WikiPrincipals work correctly in HashSet" )
        void worksInHashSet() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice", WikiPrincipal.LOGIN_NAME );
            final WikiPrincipal p3 = new WikiPrincipal( "Bob" );

            final Set< WikiPrincipal > set = new HashSet<>();
            set.add( p1 );
            set.add( p2 ); // same name as p1, should not increase set size
            set.add( p3 );

            assertEquals( 2, set.size(), "Set should contain only 2 distinct principals (Alice, Bob)" );
            assertTrue( set.contains( p1 ) );
            assertTrue( set.contains( p2 ) );
            assertTrue( set.contains( p3 ) );
        }

        @Test
        @DisplayName( "WikiPrincipals work correctly as HashMap keys" )
        void worksAsHashMapKey() {
            final WikiPrincipal key1 = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal key2 = new WikiPrincipal( "Alice", WikiPrincipal.WIKI_NAME );

            final Map< WikiPrincipal, String > map = new HashMap<>();
            map.put( key1, "first" );
            map.put( key2, "second" ); // should overwrite since keys are equal

            assertEquals( 1, map.size() );
            assertEquals( "second", map.get( key1 ) );
            assertEquals( "second", map.get( key2 ) );
        }
    }

    // ---------------------------------------------------------------
    // compareTo
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "compareTo" )
    class CompareToContract {

        @Test
        @DisplayName( "Same names compare as zero" )
        void sameNamesCompareAsZero() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Alice" );
            assertEquals( 0, p1.compareTo( p2 ) );
        }

        @Test
        @DisplayName( "Alphabetical ordering: Alice < Bob" )
        void alphabeticalOrdering() {
            final WikiPrincipal alice = new WikiPrincipal( "Alice" );
            final WikiPrincipal bob = new WikiPrincipal( "Bob" );
            assertTrue( alice.compareTo( bob ) < 0, "Alice should come before Bob" );
            assertTrue( bob.compareTo( alice ) > 0, "Bob should come after Alice" );
        }

        @Test
        @DisplayName( "compareTo with non-WikiPrincipal Principal" )
        void compareToOtherPrincipalType() {
            final WikiPrincipal wiki = new WikiPrincipal( "Bob" );
            final Principal other = () -> "Alice";
            assertTrue( wiki.compareTo( other ) > 0, "Bob should come after Alice regardless of Principal type" );
        }

        @Test
        @DisplayName( "Same names with different types compare as zero" )
        void sameNameDifferentTypeCompareAsZero() {
            final WikiPrincipal full = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            final WikiPrincipal login = new WikiPrincipal( "Alice", WikiPrincipal.LOGIN_NAME );
            assertEquals( 0, full.compareTo( login ) );
        }

        @Test
        @DisplayName( "compareTo is antisymmetric" )
        void antisymmetric() {
            final WikiPrincipal p1 = new WikiPrincipal( "Alice" );
            final WikiPrincipal p2 = new WikiPrincipal( "Bob" );
            assertTrue( p1.compareTo( p2 ) < 0 );
            assertTrue( p2.compareTo( p1 ) > 0 );
        }

        @Test
        @DisplayName( "compareTo is transitive" )
        void transitive() {
            final WikiPrincipal alice = new WikiPrincipal( "Alice" );
            final WikiPrincipal bob = new WikiPrincipal( "Bob" );
            final WikiPrincipal charlie = new WikiPrincipal( "Charlie" );
            assertTrue( alice.compareTo( bob ) < 0 );
            assertTrue( bob.compareTo( charlie ) < 0 );
            assertTrue( alice.compareTo( charlie ) < 0, "Transitivity violated" );
        }
    }

    // ---------------------------------------------------------------
    // toString
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "toString" )
    class ToStringContract {

        @Test
        @DisplayName( "toString format for UNSPECIFIED type" )
        void toStringUnspecified() {
            final WikiPrincipal p = new WikiPrincipal( "Alice" );
            assertEquals( "[WikiPrincipal (unspecified): Alice]", p.toString() );
        }

        @Test
        @DisplayName( "toString format for FULL_NAME type" )
        void toStringFullName() {
            final WikiPrincipal p = new WikiPrincipal( "Alice Wonderland", WikiPrincipal.FULL_NAME );
            assertEquals( "[WikiPrincipal (fullName): Alice Wonderland]", p.toString() );
        }

        @Test
        @DisplayName( "toString format for LOGIN_NAME type" )
        void toStringLoginName() {
            final WikiPrincipal p = new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME );
            assertEquals( "[WikiPrincipal (loginName): alice]", p.toString() );
        }

        @Test
        @DisplayName( "toString format for WIKI_NAME type" )
        void toStringWikiName() {
            final WikiPrincipal p = new WikiPrincipal( "AliceWonderland", WikiPrincipal.WIKI_NAME );
            assertEquals( "[WikiPrincipal (wikiName): AliceWonderland]", p.toString() );
        }
    }

    // ---------------------------------------------------------------
    // GUEST constant
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "GUEST constant" )
    class GuestConstant {

        @Test
        @DisplayName( "GUEST has name 'Guest'" )
        void guestName() {
            assertEquals( "Guest", WikiPrincipal.GUEST.getName() );
        }

        @Test
        @DisplayName( "GUEST is a Principal" )
        void guestIsPrincipal() {
            assertInstanceOf( Principal.class, WikiPrincipal.GUEST );
        }

        @Test
        @DisplayName( "GUEST is equal to a WikiPrincipal with name 'Guest'" )
        void guestEquality() {
            final WikiPrincipal guest = new WikiPrincipal( "Guest" );
            assertEquals( WikiPrincipal.GUEST, guest );
        }

        @Test
        @DisplayName( "GUEST is not equal to a WikiPrincipal with name 'guest' (case-sensitive)" )
        void guestCaseSensitive() {
            final WikiPrincipal lowerGuest = new WikiPrincipal( "guest" );
            assertNotEquals( WikiPrincipal.GUEST, lowerGuest );
        }
    }

    // ---------------------------------------------------------------
    // Type accessors
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "Type accessors" )
    class TypeAccessors {

        @Test
        @DisplayName( "getType returns the type string for each valid type" )
        void getTypeReturnsCorrectValue() {
            assertEquals( "fullName", new WikiPrincipal( "a", WikiPrincipal.FULL_NAME ).getType() );
            assertEquals( "loginName", new WikiPrincipal( "a", WikiPrincipal.LOGIN_NAME ).getType() );
            assertEquals( "wikiName", new WikiPrincipal( "a", WikiPrincipal.WIKI_NAME ).getType() );
            assertEquals( "unspecified", new WikiPrincipal( "a", WikiPrincipal.UNSPECIFIED ).getType() );
        }

        @Test
        @DisplayName( "getName returns the name string" )
        void getNameReturnsCorrectValue() {
            assertEquals( "Alice", new WikiPrincipal( "Alice" ).getName() );
            assertEquals( "Bob Jones", new WikiPrincipal( "Bob Jones", WikiPrincipal.FULL_NAME ).getName() );
        }
    }

    // ---------------------------------------------------------------
    // COMPARATOR static field
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "COMPARATOR static field" )
    class ComparatorField {

        @Test
        @DisplayName( "COMPARATOR is not null" )
        void comparatorIsNotNull() {
            assertNotNull( WikiPrincipal.COMPARATOR );
        }

        @Test
        @DisplayName( "COMPARATOR produces same ordering as compareTo" )
        void comparatorMatchesCompareTo() {
            final WikiPrincipal alice = new WikiPrincipal( "Alice" );
            final WikiPrincipal bob = new WikiPrincipal( "Bob" );

            final int compareToResult = alice.compareTo( bob );
            final int comparatorResult = WikiPrincipal.COMPARATOR.compare( alice, bob );
            // Both should have the same sign
            assertEquals( Integer.signum( compareToResult ), Integer.signum( comparatorResult ) );
        }
    }

    // ---------------------------------------------------------------
    // Serializable
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "Serializable" )
    class SerializableContract {

        @Test
        @DisplayName( "WikiPrincipal is Serializable" )
        void isSerializable() {
            final WikiPrincipal p = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );
            assertInstanceOf( java.io.Serializable.class, p );
        }

        @Test
        @DisplayName( "WikiPrincipal round-trips through serialization" )
        void roundTripSerialization() throws Exception {
            final WikiPrincipal original = new WikiPrincipal( "Alice", WikiPrincipal.FULL_NAME );

            // Serialize
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try( final java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream( bos ) ) {
                oos.writeObject( original );
            }

            // Deserialize
            final java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream( bos.toByteArray() );
            final WikiPrincipal deserialized;
            try( final java.io.ObjectInputStream ois = new java.io.ObjectInputStream( bis ) ) {
                deserialized = (WikiPrincipal) ois.readObject();
            }

            assertEquals( original.getName(), deserialized.getName() );
            assertEquals( original.getType(), deserialized.getType() );
            assertEquals( original, deserialized );
            assertEquals( original.hashCode(), deserialized.hashCode() );
        }
    }

    // ---------------------------------------------------------------
    // Type constant values
    // ---------------------------------------------------------------
    @Nested
    @DisplayName( "Type constant values" )
    class TypeConstants {

        @Test
        @DisplayName( "Type constants have expected string values" )
        void typeConstantValues() {
            assertEquals( "fullName", WikiPrincipal.FULL_NAME );
            assertEquals( "loginName", WikiPrincipal.LOGIN_NAME );
            assertEquals( "wikiName", WikiPrincipal.WIKI_NAME );
            assertEquals( "unspecified", WikiPrincipal.UNSPECIFIED );
        }
    }

}

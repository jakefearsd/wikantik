package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRelationshipDetectorTest {

    private final FrontmatterRelationshipDetector detector = new FrontmatterRelationshipDetector();

    @Test
    void listOfStrings_isRelationship() {
        final Map< String, Object > fm = Map.of( "depends-on", List.of( "Customer", "Product" ) );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().containsKey( "depends-on" ) );
        assertEquals( List.of( "Customer", "Product" ), result.relationships().get( "depends-on" ) );
        assertTrue( result.properties().isEmpty() );
    }

    @Test
    void scalarString_isProperty() {
        final Map< String, Object > fm = Map.of( "domain", "billing" );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().isEmpty() );
        assertEquals( "billing", result.properties().get( "domain" ) );
    }

    @Test
    void excludedKeys_alwaysProperties() {
        final Map< String, Object > fm = Map.of(
            "tags", List.of( "billing", "auth" ),
            "type", "domain-model",
            "summary", "An order entity"
        );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().isEmpty() );
        assertEquals( 3, result.properties().size() );
    }

    @Test
    void mixedFrontmatter_separatesCorrectly() {
        final Map< String, Object > fm = Map.of(
            "type", "domain-model",
            "domain", "billing",
            "depends-on", List.of( "Customer" ),
            "tags", List.of( "core" ),
            "related", List.of( "PaymentGateway" )
        );
        final var result = detector.detect( fm );
        assertEquals( 2, result.relationships().size() );
        assertTrue( result.relationships().containsKey( "depends-on" ) );
        assertTrue( result.relationships().containsKey( "related" ) );
        assertEquals( 3, result.properties().size() ); // type, domain, tags
    }

    @Test
    void singleStringInRelationshipKey_treatedAsRelationship() {
        // A single string (not a list) for a non-excluded key should be a property
        final Map< String, Object > fm = Map.of( "owner", "TeamAlpha" );
        final var result = detector.detect( fm );
        assertEquals( "TeamAlpha", result.properties().get( "owner" ) );
    }
}

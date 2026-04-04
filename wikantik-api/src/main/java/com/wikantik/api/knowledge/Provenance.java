package com.wikantik.api.knowledge;

public enum Provenance {
    HUMAN_AUTHORED( "human-authored" ),
    AI_INFERRED( "ai-inferred" ),
    AI_REVIEWED( "ai-reviewed" );

    private final String value;

    Provenance( final String value ) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Provenance fromValue( final String value ) {
        for ( final Provenance p : values() ) {
            if ( p.value.equals( value ) ) {
                return p;
            }
        }
        throw new IllegalArgumentException( "Unknown provenance: " + value );
    }
}

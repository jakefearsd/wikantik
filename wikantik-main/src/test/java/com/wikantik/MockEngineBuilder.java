package com.wikantik;

import com.wikantik.api.core.Engine;
import java.util.Properties;
import static org.mockito.Mockito.*;

/**
 * Lightweight builder that creates a mock {@link Engine} pre-wired with
 * stub manager instances. Each {@code with()} call registers a manager
 * that will be returned by {@code engine.getManager(type)}.
 *
 * <p>Usage:
 * <pre>{@code
 * Engine engine = MockEngineBuilder.engine()
 *     .with(PageManager.class, mockPageManager)
 *     .with(SearchManager.class, mockSearchManager)
 *     .properties(props)
 *     .build();
 * }</pre>
 */
public final class MockEngineBuilder {

    private final Engine engine;
    private Properties properties;

    private MockEngineBuilder() {
        engine = mock( Engine.class );
        properties = new Properties();
        when( engine.getWikiProperties() ).thenReturn( properties );
        when( engine.getApplicationName() ).thenReturn( "test" );
        when( engine.isConfigured() ).thenReturn( true );
    }

    public static MockEngineBuilder engine() {
        return new MockEngineBuilder();
    }

    public < T > MockEngineBuilder with( final Class< T > managerType, final T instance ) {
        when( engine.getManager( managerType ) ).thenReturn( instance );
        return this;
    }

    public MockEngineBuilder properties( final Properties props ) {
        this.properties = props;
        when( engine.getWikiProperties() ).thenReturn( props );
        return this;
    }

    public MockEngineBuilder property( final String key, final String value ) {
        properties.setProperty( key, value );
        return this;
    }

    public Engine build() {
        return engine;
    }

}

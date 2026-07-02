package com.wikantik.core.registry;

import com.wikantik.WikiEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the getManager/setManager contract across the storage refactor: a value
 * set via setManager is returned by getManager; unknown types return null.
 * This is a characterization test — it must pass on the pre-refactor code and
 * stay green through the change.
 */
class WikiEngineRegistryParityTest {

    @Test
    void setManagerThenGetManagerRoundTrips() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        final com.wikantik.diff.DifferenceManager mock =
                org.mockito.Mockito.mock( com.wikantik.diff.DifferenceManager.class );
        engine.setManager( com.wikantik.diff.DifferenceManager.class, mock );
        assertSame( mock, engine.getManager( com.wikantik.diff.DifferenceManager.class ) );
    }

    @Test
    void getManagerUnknownReturnsNull() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        assertNull( engine.getManager( Runnable.class ) );
    }

    @Test
    void managerRegisteredDuringBootIsRetrievable() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        // PageManager is wired during boot; must resolve after the refactor too.
        assertNotNull( engine.getManager( com.wikantik.api.managers.PageManager.class ) );
    }
}

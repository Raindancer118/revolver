package io.github.raindancer118.revolver;

import java.io.IOException;
import java.util.List;

/**
 * Storage backend for persisting and restoring drum state across restarts.
 * Implement this to plug in a custom backend (e.g. Redis, a database).
 */
public interface Persister {

    void save(List<ChamberState> states) throws IOException;

    /**
     * @return the previously saved states, or an empty list if none exist yet
     */
    List<ChamberState> load() throws IOException;
}

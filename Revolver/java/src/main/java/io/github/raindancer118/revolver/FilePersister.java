package io.github.raindancer118.revolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Persists drum state as a JSON file, writing atomically via a temporary file and rename.
 */
public final class FilePersister implements Persister {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path path;

    public FilePersister(Path path) {
        this.path = path;
    }

    @Override
    public void save(List<ChamberState> states) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), states);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public List<ChamberState> load() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        ChamberState[] states = MAPPER.readValue(path.toFile(), ChamberState[].class);
        return List.of(states);
    }
}

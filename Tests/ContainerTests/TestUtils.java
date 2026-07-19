package ContainerTests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class TestUtils {
    public static void clearDatFiles() {
        Path testDataDir = Paths.get("Tests", "TestData").toAbsolutePath().normalize();
        if (!Files.exists(testDataDir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(testDataDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

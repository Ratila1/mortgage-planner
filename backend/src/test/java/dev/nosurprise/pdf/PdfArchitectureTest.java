package dev.nosurprise.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfArchitectureTest {

    @Test
    @DisplayName("пакет pdf не обращается к calc.repository")
    void pdfDoesNotUseCalcRepository() throws Exception {
        Path root = Path.of("src/main/java/dev/nosurprise/pdf");
        try (Stream<Path> paths = Files.walk(root)) {
            boolean found = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p).contains("calc.repository");
                        } catch (Exception ex) {
                            return false;
                        }
                    });
            assertThat(found).isFalse();
        }
    }
}
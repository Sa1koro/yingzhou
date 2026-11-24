package tvbox.runtime;

import com.googlecode.d2j.dex.Dex2jar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class WindowsDexOutput {
    private WindowsDexOutput() {}

    static void write(Dex2jar converter, Path pending, String name) throws IOException {
        try {
            converter.to(pending);
        } catch (ReadOnlyFileSystemException readOnly) {
            if (java.io.File.separatorChar != '\\') throw readOnly;
            String entry = name.replace('.', '/') + ".class";
            if (!entry.matches("(?:[\\p{L}\\p{N}_$-]+/)*[\\p{L}\\p{N}_$-]+\\.class")) throw readOnly;
            Path scratch = Files.createTempDirectory(pending.getParent(), "class-output-");
            try {
                converter.to(scratch);
                Path output = scratch.resolve(entry);
                try (var zip = new ZipOutputStream(Files.newOutputStream(pending))) {
                    zip.putNextEntry(new ZipEntry(entry));
                    Files.copy(output, zip);
                    zip.closeEntry();
                }
            } finally {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}

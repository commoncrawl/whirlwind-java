// From https://github.com/commoncrawl/whirlwind-python

package org.commoncrawl.whirlwind;

import org.netpreserve.jwarc.MessageHeaders;
import org.netpreserve.jwarc.WarcReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class ReadWARC {

    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            System.err.println("Usage: java ReadWARC <input-warc-file>");
            System.exit(1);
        }

        Path requested = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(requested)) {
            throw new SecurityException("Invalid WARC path");
        }

        final List<String> RESPONSE_TYPES = Arrays.asList("request", "response", "conversion", "metadata");

        try (
                InputStream in = Files.newInputStream(requested);
                WarcReader reader = new WarcReader(in)
        ) {
            reader.records().forEach(record -> {
                System.out.println(" WARC-Type: " + record.type());
                if (RESPONSE_TYPES.contains(record.type())) {
                    MessageHeaders headers = record.headers();
                    for (String header : headers.all("WARC-Target-URI")) {
                        System.out.println(" WARC-Target-URI " + header);
                    }
                }
            });
        }
    }
}

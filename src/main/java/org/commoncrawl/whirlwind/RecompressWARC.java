// From https://github.com/commoncrawl/whirlwind-python

package org.commoncrawl.whirlwind;

import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public class RecompressWARC {

    public static void main(String[] args) throws IOException {

        if (args.length != 2) {
            System.err.println("Usage: java RecompressWarc <input-uncompressed-warc-file> <output-compressed-warc-file>");
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputPath = Path.of(args[1]).toAbsolutePath().normalize();

        if (!Files.isRegularFile(inputPath)) {
            throw new SecurityException("Invalid input WARC path");
        }

        if (inputPath.endsWith(".gz")) {
            System.out.println("Input WARC file is already compressed");
            System.exit(1);
        }

        try (
                InputStream in = Files.newInputStream(inputPath);
                WarcReader reader = new WarcReader(in);
                OutputStream out = Files.newOutputStream(outputPath);
                WritableByteChannel outChannel = Channels.newChannel(out);
                WarcWriter writer = new WarcWriter(outChannel, WarcCompression.GZIP)
        ) {
            reader.forEach(record -> {
                try {
                    writer.write(record);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}

package org.commoncrawl.whirlwind;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class ValidateWARC {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java ValidateWARC <file.gz>");
            System.exit(2);
        }

        Path requested = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(requested)) {
            throw new SecurityException("Invalid WARC path");
        }

        int n = getWarcCompressionInformation(requested);
        if (n <= 1) {
            System.out.println("Single-member gzip (likely whole-file gzip). members=" + n);
        } else {
            System.out.println("Concatenated multi-member gzip (record-compressed). members=" + n);
        }

    }

    public static int getWarcCompressionInformation(Path inputWarc) throws IOException {
        final AtomicInteger memberCount = new AtomicInteger(0);

        try (
                InputStream fis = Files.newInputStream(inputWarc);
                BufferedInputStream bis = new BufferedInputStream(fis);
                GzipCompressorInputStream gz = GzipCompressorInputStream.builder()
                        .setDecompressConcatenated(true)
                        .setOnMemberEnd(x -> memberCount.incrementAndGet())
                        .setInputStream(bis).get()
        ) {

            byte[] buf = new byte[64 * 1024];
            while (gz.read(buf) != -1) {
                // Read the entire stream to trigger member processing
                // We might not need to read the whole stream, just enough to get an idea
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The file is either not a gzip file or is corrupted.", e);
        }

        return memberCount.get();
    }

    public static void validateRandomAccessWarcOrFail(Path inputWarc) throws IOException {
        int n = getWarcCompressionInformation(inputWarc);

        if (n <= 1) {
            throw new IOException("Non-chunked gzip file detected, gzip block continues\n" +
                    "    beyond single record. " + n);
        }

    }
}

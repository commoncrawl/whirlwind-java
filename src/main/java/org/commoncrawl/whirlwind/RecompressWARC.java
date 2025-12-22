/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

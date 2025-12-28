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

import org.apache.commons.lang3.StringUtils;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.cdx.CdxFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CdxjIndexer {

    public static void main(String[] args) throws IOException {
        String inputFile = null;
        Set<String> recordTypes = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--records") && i + 1 < args.length) {
                // Support comma-separated record types: --records conversion,metadata
                String typesArg = args[++i];
                recordTypes = new HashSet<>(Arrays.asList(typesArg.split(",")));
            } else if (args[i].equals("--help") || args[i].equals("-h")) {
                printUsage();
                System.exit(0);
            } else if (!args[i].startsWith("-")) {
                inputFile = args[i];
            } else {
                System.err.println("Unknown option: " + args[i]);
                printUsage();
                System.exit(1);
            }
        }

        if (inputFile == null) {
            System.err.println("Error: Input file is required");
            printUsage();
            System.exit(1);
        }

        Path requested = Path.of(inputFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(requested)) {
            throw new SecurityException("Invalid WARC path: " + requested);
        }

        //TODO: Move this into the WarcReader or the process of iterating over the records
        if (requested.toString().endsWith("gz") || requested.toString().endsWith("gzip")) {
            try {
                ValidateWARC.validateRandomAccessWarcOrFail(requested);
            } catch (IOException e) {
                System.err.println("This file is probably not a multi-member gzip but a single gzip file.\n" +
                        "To allow seek, a gzipped WARC must have each record compressed into a single gzip member and concatenated together.\n\n"
                        +
                        "This file is likely still valid and can be fixed by running:\n" +
                        "mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.RecompressWARC -Dexec.args=\"" +
                        inputFile + " " + inputFile.replace(".gz", ".recompressed.gz") + "\"");
                System.exit(-1);
            }
        }

        try (
                InputStream in = Files.newInputStream(requested);
                CdxFilterWithDynamicFiltering cdxjWriter = new CdxFilterWithDynamicFiltering(new OutputStreamWriter(System.out));
                WarcReader reader = new WarcReader(in)
        ) {
            reader.setLenient(true);
            cdxjWriter.setFormat(CdxFormat.CDXJ);
            if (recordTypes != null) {
                Set<String> unmodifiableRecordTypes = Collections.unmodifiableSet(recordTypes);
                cdxjWriter.setRecordFilter(record ->
                        unmodifiableRecordTypes.contains(StringUtils.lowerCase(record.type())
                        )
                );
            }
            cdxjWriter.process(reader, requested.toString());
        }
    }

    private static void printUsage() {
        System.err.println("Usage: cdxj-indexer [OPTIONS] <input-warc-file>");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --records <types>  Comma-separated list of record types to index");
        System.err.println("                     (e.g., conversion, response, metadata)");
        System.err.println("  --help, -h         Show this help message");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  cdxj-indexer file.warc.gz");
        System.err.println("  cdxj-indexer --records conversion file.wet.gz");
        System.err.println("  cdxj-indexer --records response,resource file.warc.gz");
    }
}

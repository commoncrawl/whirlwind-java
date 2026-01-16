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

	public static int getWarcCompressionInformation(Path inputWarc) throws IllegalArgumentException {
		final AtomicInteger memberCount = new AtomicInteger(0);

		try (InputStream fis = Files.newInputStream(inputWarc);
				BufferedInputStream bis = new BufferedInputStream(fis);
				GzipCompressorInputStream gz = GzipCompressorInputStream.builder().setDecompressConcatenated(true)
						.setOnMemberEnd(x -> memberCount.incrementAndGet()).setInputStream(bis).get()) {

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
			throw new IOException(
					"Non-chunked gzip file detected, gzip block continues\n" + "    beyond single record. " + n);
		}

	}
}

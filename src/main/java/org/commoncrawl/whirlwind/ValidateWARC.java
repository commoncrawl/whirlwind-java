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

public class ValidateWARC {

	private static final int READ_BUFFER_SIZE = 64 * 1024;

	private static class StopReadingException extends IOException {
	}

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
		if (n == 0) {
			System.out.println("No gzip members found (empty or not a gzip file). members=0");
		} else if (n == 1) {
			System.out.println("Single-member gzip (likely whole-file gzip). members=1");
		} else {
			System.out.println("Concatenated multi-member gzip (record-compressed). members=" + n);
		}

	}

	/**
	 * Counts gzip members in the file. If stopAfter > 0, stops early once that many
	 * members have been found (avoids decompressing the entire file). Pass
	 * stopAfter = 0 to count all members.
	 */
	private static int countGzipMembers(Path inputWarc, int stopAfter) throws IOException {
		int[] memberCount = { 0 };

		try (InputStream fis = Files.newInputStream(inputWarc);
				BufferedInputStream bis = new BufferedInputStream(fis);
				GzipCompressorInputStream gz = GzipCompressorInputStream.builder().setDecompressConcatenated(true)
						.setOnMemberEnd(x -> {
							memberCount[0]++;
							if (stopAfter > 0 && memberCount[0] >= stopAfter) {
								throw new StopReadingException();
							}
						}).setInputStream(bis).get()) {

			byte[] buf = new byte[READ_BUFFER_SIZE];
			while (gz.read(buf) != -1) {
				// drain the stream to trigger member callbacks
			}
		} catch (StopReadingException e) {
			// early exit — threshold reached
		}

		return memberCount[0];
	}

	public static int getWarcCompressionInformation(Path inputWarc) throws IOException {
		return countGzipMembers(inputWarc, 0);
	}

	/**
	 * Fast check: returns true if the gzip file contains more than one member.
	 * Stops reading as soon as the second member is detected, avoiding full
	 * decompression of large files.
	 */
	public static boolean isMultiMemberGzip(Path inputWarc) throws IOException {
		return countGzipMembers(inputWarc, 2) >= 2;
	}

	public static void validateRandomAccessWarcOrFail(Path inputWarc) throws IOException {
		if (!isMultiMemberGzip(inputWarc)) {
			throw new IOException("Non-chunked gzip file detected, gzip block continues\n"
					+ "    beyond single record. File must be record-compressed (multi-member gzip).");
		}
	}
}

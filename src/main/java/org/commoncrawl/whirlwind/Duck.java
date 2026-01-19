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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class Duck {

	private static final int MAX_RETRIES = 100;
	private static final int RETRY_SLEEP_MS = 10000;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	public enum Algorithm {
		CCF_LOCAL_FILES("ccf_local_files"), CLOUDFRONT("cloudfront");

		private final String name;

		Algorithm(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public static Algorithm fromString(String name) {
			for (Algorithm algo : values()) {
				if (algo.name.equalsIgnoreCase(name)) {
					return algo;
				}
			}
			throw new IllegalArgumentException("Unknown algorithm: " + name + ". Possible values: "
					+ Arrays.stream(values()).map(Algorithm::getName).collect(Collectors.joining(", ")));
		}
	}

	/**
	 * Prints download instructions for the Common Crawl index.
	 */
	public static void printIndexDownloadAdvice(String prefix, String crawl) {
		System.out.println("Do you need to download this index?");
		System.out.printf(" mkdir -p %s/commoncrawl/cc-index/table/cc-main/warc/crawl=%s/subset=warc/%n", prefix,
				crawl);
		System.out.printf(" cd %s/commoncrawl/cc-index/table/cc-main/warc/crawl=%s/subset=warc/%n", prefix, crawl);
		System.out.printf(" aws s3 sync s3://commoncrawl/cc-index/table/cc-main/warc/crawl=%s/subset=warc/ .%n", crawl);
	}

	/**
	 * Prints a row in CDXJ format.
	 */
	public static void printRowAsJWarc(ResultSet rs, PrintStream out) throws SQLException {
		while (rs.next()) {
			Map<String, Object> cdxjData = new LinkedHashMap<>();
			cdxjData.put("url", rs.getString("url"));
			cdxjData.put("mime", rs.getString("content_mime_type"));
			cdxjData.put("status", String.valueOf(rs.getInt("fetch_status")));
			cdxjData.put("digest", "sha1:" + rs.getString("content_digest"));
			cdxjData.put("length", String.valueOf(rs.getLong("warc_record_length")));
			cdxjData.put("offset", String.valueOf(rs.getLong("warc_record_offset")));
			cdxjData.put("filename", rs.getString("warc_filename"));

			Timestamp fetchTime = rs.getTimestamp("fetch_time");
			String timestamp = fetchTime.toLocalDateTime().format(TIMESTAMP_FORMATTER);

			String surtKey = rs.getString("url_surtkey");
			out.printf("%s %s %s%n", surtKey, timestamp, GSON.toJson(cdxjData));
		}
	}

	/**
	 * Prints all columns of a row as key-value pairs.
	 */
	public static void printRowAsKvList(ResultSet rs, PrintStream out) throws SQLException {
		ResultSetMetaData metaData = rs.getMetaData();
		int columnCount = metaData.getColumnCount();

		while (rs.next()) {
			for (int i = 1; i <= columnCount; i++) {
				String columnName = metaData.getColumnName(i);
				Object value = rs.getObject(i);
				out.printf("  %s %s%n", columnName, value);
			}
		}
	}

	/**
	 * Gets the list of parquet files to query based on the algorithm.
	 */
	public static List<String> getFiles(Algorithm algo, String crawl) throws IOException {
		switch (algo) {
		case CCF_LOCAL_FILES: {
			Path indexPath = Path.of("/home/cc-pds/commoncrawl/cc-index/table/cc-main/warc", "crawl=" + crawl,
					"subset=warc");
			return getLocalParquetFiles(indexPath, "/home/cc-pds", crawl);
		}

		case CLOUDFRONT: {
			String externalPrefix = String
					.format("https://data.commoncrawl.org/cc-index/table/cc-main/warc/crawl=%s/subset=warc/", crawl);
			String pathsFile = Paths.get("data", crawl + ".warc.paths.gz").toString();

			List<String> files = new ArrayList<>();
			try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(pathsFile));
					BufferedReader reader = new BufferedReader(new InputStreamReader(gzis, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					files.add(externalPrefix + line);
				}
			}
			return files;
		}

		default:
			throw new IllegalArgumentException("Unknown algorithm: " + algo);
		}
	}

	private static List<String> getLocalParquetFiles(Path indexPath, String prefix, String crawl) throws IOException {
		if (!Files.isDirectory(indexPath)) {
			printIndexDownloadAdvice(prefix, crawl);
			System.exit(1);
		}

		List<String> files = Files.list(indexPath).filter(p -> p.toString().endsWith(".parquet")).map(Path::toString)
				.collect(Collectors.toList());

		if (files.size() < 250) {
			printIndexDownloadAdvice(prefix, crawl);
			System.exit(1);
		}

		return files;
	}

	/**
	 * Executes a query with retry logic.
	 */
	private static ResultSet executeWithRetry(Statement stmt, String sql) throws SQLException, InterruptedException {
		int retriesLeft = MAX_RETRIES;

		while (true) {
			try {
				return stmt.executeQuery(sql);
			} catch (SQLException e) {
				String message = e.getMessage();
				if (message != null && (message.contains("HTTP") || message.contains("magic bytes"))) {
					System.err.println("DuckDB exception seen: " + e.getMessage());
					if (retriesLeft > 0) {
						System.err.printf("Sleeping for %d seconds...%n", RETRY_SLEEP_MS / 1000);
						Thread.sleep(RETRY_SLEEP_MS);
						retriesLeft--;
					} else {
						throw e;
					}
				} else {
					throw e;
				}
			}
		}
	}

	public static void main(String[] args) {
		String crawl = "CC-MAIN-2024-22";
		Algorithm algo = Algorithm.CLOUDFRONT;

		if (args.length > 0) {
			if ("help".equalsIgnoreCase(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
				printUsage();
				System.exit(0);
			}
			algo = Algorithm.fromString(args[0]);
		} else {
			System.out.println("Using algorithm: " + algo.getName());
		}

		try {
			run(algo, crawl);
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			printUsage();
			System.exit(1);
		}
	}

	public static void run(Algorithm algo, String crawl) throws IOException, SQLException, InterruptedException {
		// Ensure stdout uses UTF-8
		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

		List<String> files = getFiles(algo, crawl);
		String filesList = files.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "));

		// Use in-memory DuckDB
		try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement stmt = conn.createStatement()) {

			// Configure DuckDB
			stmt.execute("SET enable_progress_bar = true");
			stmt.execute("SET http_retries = 100");

			// Create view from parquet files
			String createViewSql = String.format("CREATE VIEW ccindex AS SELECT * FROM read_parquet([%s])", filesList);
			stmt.execute(createViewSql);

			// Count total records
			out.printf("Total records for crawl: %s%n", crawl);
			try (ResultSet rs = executeWithRetry(stmt, "SELECT COUNT(*) as cnt FROM ccindex")) {
				if (rs.next()) {
					out.println(rs.getLong("cnt"));
				}
			}

			// Query for our specific row
			String selectQuery = "" + "SELECT * FROM ccindex WHERE subset = 'warc' " + "AND crawl = 'CC-MAIN-2024-22' "
					+ "AND url_host_tld = 'org' " + "AND url_host_registered_domain = 'wikipedia.org' "
					+ "AND url = 'https://an.wikipedia.org/wiki/Escopete'";

			out.println("Our one row:");
			try (ResultSet rs = executeWithRetry(stmt, selectQuery)) {
				printResultSet(rs, out);
			}

			// Write to local parquet
			out.println("Writing our one row to a local parquet file, whirlwind.parquet");
			stmt.execute(String.format("COPY (%s) TO 'whirlwind.parquet' (FORMAT PARQUET)", selectQuery));

			// Verify local parquet
			stmt.execute("CREATE VIEW cclocal AS SELECT * FROM read_parquet('whirlwind.parquet')");

			out.println("Total records for local whirlwind.parquet should be 1:");
			try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM cclocal")) {
				if (rs.next()) {
					out.println(rs.getLong("cnt"));
				}
			}

			String localQuery = selectQuery.replace("ccindex", "cclocal");
			out.println("Our one row, locally:");
			try (ResultSet rs = stmt.executeQuery(localQuery)) {
				printResultSet(rs, out);
			}

			out.println("\nComplete row:");
			try (ResultSet rs = stmt.executeQuery(localQuery)) {
				printRowAsKvList(rs, out);
			}

			out.println("\nEquivalent to JWARC:");
			try (ResultSet rs = stmt.executeQuery(localQuery)) {
				printRowAsJWarc(rs, out);
			}
		}
	}

	/**
	 * Prints a ResultSet in a simple table-like format.
	 */
	private static void printResultSet(ResultSet rs, PrintStream out) throws SQLException {
		ResultSetMetaData metaData = rs.getMetaData();
		int columnCount = metaData.getColumnCount();

		// Print column headers
		List<String> headers = new ArrayList<>();
		for (int i = 1; i <= columnCount; i++) {
			headers.add(metaData.getColumnName(i));
		}
		out.println(String.join(" | ", headers));
		out.println("-".repeat(headers.stream().mapToInt(String::length).sum() + (headers.size() - 1) * 3));

		// Print rows
		while (rs.next()) {
			List<String> values = new ArrayList<>();
			for (int i = 1; i <= columnCount; i++) {
				Object value = rs.getObject(i);
				values.add(value == null ? "NULL" : value.toString());
			}
			out.println(String.join(" | ", values));
		}
	}

	private static void printUsage() {
		System.err.println("Usage: Duck [algorithm]");
		System.err.println();
		System.err.println("Query Common Crawl index using DuckDB.");
		System.err.println();
		System.err.println("Algorithms:");
		System.err.println("  ccf_local_files  Use local parquet files from /home/cc-pds/commoncrawl/...");
		System.err.println("  cloudfront       Use CloudFront URLs (requires <crawl>.warc.paths.gz file)");
		System.err.println();
		System.err.println("Options:");
		System.err.println("  help, --help, -h  Show this help message");
	}
}

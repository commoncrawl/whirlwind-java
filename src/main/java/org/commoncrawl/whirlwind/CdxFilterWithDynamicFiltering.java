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

import org.netpreserve.jwarc.*;
import org.netpreserve.jwarc.cdx.CdxFormat;
import org.netpreserve.jwarc.cdx.CdxRequestEncoder;
import org.netpreserve.jwarc.cdx.CdxWriter;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Predicate;


public class CdxFilterWithDynamicFiltering extends CdxWriter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);

    private final Writer writer;
    private CdxFormat format = CdxFormat.CDXJ;
    private boolean postAppend = false;
    private Consumer<String> warningHandler;
    private Predicate<WarcRecord> recordFilter = null;

    public CdxFilterWithDynamicFiltering(Writer writer) {
        super(writer);
        this.writer = writer;
    }

    @Override
    public void setFormat(CdxFormat format) {
        super.setFormat(format);
        this.format = format;
    }

    public CdxFormat getFormat() {
        return this.format;
    }

    @Override
    public void setPostAppend(boolean postAppend) {
        super.setPostAppend(postAppend);
        this.postAppend = postAppend;
    }

    @Override
    public void onWarning(Consumer<String> warningHandler) {
        super.onWarning(warningHandler);
        this.warningHandler = warningHandler;
    }

    @Override
    public void process(WarcReader reader, String filename) throws IOException {

        if (recordFilter == null) {
            super.process(reader, filename);
            return;
        }

        // Custom processing for filtered record types, since we are filtering, we get and process
        // every record here.
        WarcRecord record = reader.next().orElse(null);
        while (record != null) {
            try {
                String recordType = record.type().toLowerCase();

                long position = reader.position();

                // Handle WarcCaptureRecord types (response, resource, revisit, request)
                if (record instanceof WarcCaptureRecord) {
                    WarcCaptureRecord capture = (WarcCaptureRecord) record;
                    URI id = record.version().getProtocol().equals("ARC") ? null : record.id();

                    // Ensure HTTP header is parsed for revisit records
                    if (record instanceof WarcRevisit && record.contentType().base().equals(MediaType.HTTP)) {
                        ((WarcRevisit) record).http();
                    }

                    // Advance to next record to calculate length
                    record = reader.next().orElse(null);
                    long length = reader.position() - position;

                    // Skip records without a date
                    if (!capture.headers().first("WARC-Date").isPresent()) {
                        emitWarning(filename, position, "Skipping record due to missing or invalid date");
                        continue;
                    }

                    String encodedRequest = null;
                    if (postAppend) {
                        while (encodedRequest == null && record instanceof WarcCaptureRecord
                                && ((WarcCaptureRecord) record).concurrentTo().contains(id)) {
                            if (record instanceof WarcRequest) {
                                HttpRequest httpRequest = ((WarcRequest) record).http();
                                encodedRequest = CdxRequestEncoder.encode(httpRequest);
                            }
                            record = reader.next().orElse(null);
                        }
                    }

                    write(capture, filename, position, length, encodedRequest);
                }
                // Handle WarcConversion (from WET files) and other WarcTargetRecord types
                else if (record instanceof WarcTargetRecord) {
                    WarcTargetRecord targetRecord = (WarcTargetRecord) record;

                    // Advance to next record to calculate length
                    record = reader.next().orElse(null);
                    long length = reader.position() - position;

                    // Skip records without a date
                    if (!targetRecord.headers().first("WARC-Date").isPresent()) {
                        emitWarning(filename, position, "Skipping record due to missing or invalid date");
                        continue;
                    }

                    writeTargetRecord(targetRecord, filename, position, length);
                } else {
                    // Skip non-target records (like warcinfo)
                    record = reader.next().orElse(null);
                }
            } catch (ParsingException e) {
                emitWarning(filename, reader.position(), "ParsingException: " + e.getBaseMessage());
                record = reader.next().orElse(null);
            }
        }
    }

    @Override
    public void setRecordFilter(Predicate<WarcRecord> recordFilter) {
        super.setRecordFilter(recordFilter);
        this.recordFilter = recordFilter;
    }

    /**
     * Writes a CDXJ record for a WarcTargetRecord (like WarcConversion from WET
     * files).
     *
     * TODO: make it more generic and integrated into jwarc
     */
    private void writeTargetRecord(WarcTargetRecord record, String filename,
                                   long position, long length) throws IOException {
        String target = record.target();
        if (target == null) {
            emitWarning(filename, position, "Skipping record due to missing target URI");
            return;
        }

        // Build CDXJ line: surt timestamp {json}
        StringBuilder line = new StringBuilder();

        // SURT-formatted URL key
        String surt = URIs.toNormalizedSurt(target);
        line.append(escape(surt));
        line.append(' ');

        // Timestamp
        String timestamp = DATE_FORMAT.format(record.date());
        line.append(timestamp);
        line.append(' ');

        // JSON block
        line.append('{');

        // URL
        line.append("\"url\": \"");
        escapeJsonString(line, target);
        line.append("\"");

        // MIME type
        try {
            if (record.payload().isPresent()) {
                MediaType mime = record.payload().get().type();
                if (mime != null) {
                    line.append(", \"mime\": \"");
                    escapeJsonString(line, mime.base().toString());
                    line.append("\"");
                }
            }
        } catch (IOException e) {
            // Skip mime if payload can't be read
        }

        // Digest
        record.payloadDigest().ifPresent(digest -> {
            line.append(", \"digest\": \"");
            escapeJsonString(line, digest.raw());
            line.append("\"");
        });

        // Filename
        if (filename != null) {
            line.append(", \"filename\": \"");
            escapeJsonString(line, filename);
            line.append("\"");
        }

        // Offset
        line.append(", \"offset\": \"");
        line.append(position);
        line.append("\"");

        // Length
        line.append(", \"length\": \"");
        line.append(length);
        line.append("\"");

        line.append('}');

        writer.write(line.toString());
        writer.write('\n');
    }

    private void emitWarning(String filename, long position, String message) {
        if (warningHandler == null)
            return;
        warningHandler.accept(filename + " (offset " + position + ") " + message);
    }

    // Borrowed from org.netpreserve.jwarc.cdx.CdxWriter
    // TODO: remove duplication
    private static String escape(String str) {
        if (str == null) return null;
        return str.replace(" ", "%20")
                .replace("\n", "%0A")
                .replace("\0", "%00");
    }


    // Borrowed from org.netpreserve.jwarc.cdx.CdxWriter
    // TODO: remove duplication
    private static void escapeJsonString(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\b') out.append("\\b");
            else if (c == '\f') out.append("\\f");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c <= 0x1f) {
                out.append("\\u00");
                out.append(Character.forDigit((c & 0xf0) >>> 4, 16));
                out.append(Character.forDigit(c & 0xf, 16));
            } else {
                out.append(c);
            }
        }
    }
}

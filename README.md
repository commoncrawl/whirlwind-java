# Whirlwind Tour of Common Crawl's Datasets using Java

The Common Crawl corpus contains petabytes of crawl data, including raw web page data, metadata, and parsed text. Common Crawl's data storage is a little complicated, as you might expect for such a large and rich dataset. We make our crawl data available in a variety of formats (WARC, WET, WAT) and we also have two index files of the crawled webpages: CDXJ and columnar.
```mermaid
flowchart TD
    WEB["WEB"] -- crawler --> cc["Common Crawl"]
    cc --> WARC["WARC"] & WAT["WAT"] & WET["WET"] & CDXJ["CDXJ"] & Columnar["Columnar"] & etc["..."]
    WEB@{ shape: cyl}
    WARC@{ shape: stored-data}
    WAT@{ shape: stored-data}
    WET@{ shape: stored-data}
    CDXJ@{ shape: stored-data}
    Columnar@{ shape: stored-data}
    etc@{ shape: stored-data}
```

The goal of this whirlwind tour is to show you how a single webpage appears in all of these different places. That webpage is [https://an.wikipedia.org/wiki/Escopete](https://an.wikipedia.org/wiki/Escopete), which we crawled on the date 2024-05-18T01:58:10Z. On the way, we'll also explore the file formats we use and learn about some useful tools for interacting with our data!

In the Whirlwind Tour, we will:
1) explore the WARC, WET and WAT file formats used to store Common Crawl's data.
2) play with some useful Java libraries for interacting with the data: [jwarc](https://github.com/iipc/jwarc), TBA if needed
and [duckdb](https://duckdb.org/).
3) learn about how the data is compressed to allow random access.
4) use the CDXJ index and the columnar index to access the data we want.

**Prerequisites:** To get the most out of this tour, you should be comfortable with Maven, running commands on the command line, and basic SQL. Some knowledge of HTTP requests and HTML is also helpful but not essential. We assume you have [make](https://www.gnu.org/software/make/) and [Maven](https://maven.apache.org/) installed.

We use a [Makefile](https://makefiletutorial.com) to provide many of the commands needed to run this tutorial. To see what commands are being run, open the `Makefile` and find the relevant target: e.g. `make build` is running `mvn clean package`.

Let's get started!

## Task 0: Set-up

This tutorial was written on Linux and MacOS and it should also work on Windows. If you encounter any problems, please raise an issue.

### Clone the repository

First, [clone this repository](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository) to create a local copy, then navigate to the `whirlwind-java` directory on your computer.

Next, Maven usually takes care of downloading the JARs of all libraries when need, so you don't need to run anything beforehand. 

### Install and configure AWS-CLI

We will use the AWS Command Line Interface (CLI) later in the tour to access the data stored in Common Crawl's S3 bucket. Instructions on how to install the AWS-CLI and configure your account are available on the [AWS website](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html). 

## Task 1: Look at the crawl data

Common Crawl's website includes a [Get Started](https://commoncrawl.org/get-started) guide which summarises different ways to access the data and the file formats. We can use the dropdown menu to access the links for downloading crawls over HTTP(S):

![crawl_dropdown.png](img/crawl_dropdown.png)

If we click on `CC-MAIN-2024-22' in the dropdown, we are taken to a page listing the files contained in this crawl: 

![crawl_file_listing.png](img/crawl_file_listing.png)

In this whirlwind tour, we're going to look at the WARC, WET, and WAT files: the data types which store the crawl data. Later, we will look at the two index files and how these help us access the crawl data we want. At the [end of the Tour](#other-datasets), we'll mention some of Common Crawl's other datasets and where you can find more information about them.

### WARC

[WARC files](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.0/) are a container that holds files, similar to zip and tar files. It's the standard data format used by archiving
community, and we use it to store raw crawl data. As you can see in the file listing above, our WARC files are very large even when compressed! Luckily, we have a much smaller example to look at. 

Open `whirlwind.warc` in your favorite text editor. Note that this is an uncompressed version of the file; normally we always work with these files while they are compressed. This is the WARC corresponding to the single webpage we mentioned in the introduction.

You'll see four records total, with the start of each record marked with the header `WARC/1.0` followed by metadata related to that particular record. The `WARC-Type` field tells you the type of each record. In our WARC file, we have:
1) a `warcinfo` record. Every WARC has that at the start. 
2) the `request` to the webserver, with its HTTP headers.
3) the `response` from the webserver, with its HTTP headers followed by the html.
4) a `metadata` record related to the HTTP response.

### WET

WET (WARC Encapsulated Text) files only contain the body text of web pages parsed from the HTML and exclude any HTML code, images, or other media. This makes them useful for text analysis and natural language processing (NLP) tasks.

Open `whirlwind.warc.wet`: this is the WET derived from our original WARC. We can see that it's still in WARC format with two records: 
1) a `warcinfo` record.
2) a `conversion` record: the parsed text with HTTP headers removed.

### WAT

WAT (Web ARChive Timestamp) files contain metadata associated with the crawled web pages (e.g. parsed data from the HTTP response headers, links recovered from HTML pages, server response codes etc.). They are useful for analysis that requires understanding the structure of the web.

Open `whirlwind.warc.wat`: this is the WAT derived from our original WARC. Like the WET file, it's also in WARC format. It contains two records:
1) a `warcinfo` record.
2) a `metadata` record: there should be one for each response in the WARC. The metadata is stored as JSON. 

You might want to feed the JSON into a pretty-printer to read it more easily. For example, you can save just the JSON into a file and use `python -m json.tool FILENAME` to pretty-print it.

Now that we've looked at the uncompressed versions of these files to understand their structure, we'll be interacting with compressed WARC, WET, and WAT files for the rest of this tour. This is the usual way we manipulate this data with software tools due to the size of the files.

## Task 2: Iterate over WARC, WET, and WAT files

The [JWar](https://github.com/iipc/jwarc) Java library lets us read and write WARC files programmatically.
```mermaid
flowchart LR
    user["userprocess (r/w)"]--JWar (w) -->warc
    warc --JWar (r)--> user 
    warc@{shape: cyl}
```
Let's use it to iterate over our WARC, WET, and WAT files and print out the record types we looked at before. First, look at the code in `org.commoncrawl.whirlwind.ReadWARC`:

<details>
  <summary>Click to view code</summary>

```java
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


```
</details>

The `org.commoncrawl.whirlwind.ReadWARC` reads the WARC content using JDK Streams in a single pass and allows us to access the attributes of each record (e.g. the record type through `record.type()`).

Now run: 

`make iterate` 

This will run `org.commoncrawl.whirlwind.ReadWARC` on `whirlwind.warc.gz`, `whirlwind.warc.wet.gz`, and `whirlwind.warc.wat.gz` in turn. You should see output like the following:

<details>
  <summary>Click to view output</summary>

```
iterating over all of the local warcs:

warc:
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="whirlwind.warc.gz"
 WARC-Type: warcinfo
 WARC-Type: request
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: response
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: metadata
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete

wet:
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="whirlwind.warc.wet.gz"
 WARC-Type: warcinfo
 WARC-Type: conversion
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete

wat:
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="whirlwind.warc.wat.gz"
 WARC-Type: warcinfo
 WARC-Type: metadata
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
```
</details>

The output has three sections, one each for the WARC, WET, and WAT. For each one, it prints the record types we saw before, plus the `WARC-Target-URI` for those record types that have it.

## Task 3: Index the WARC, WET, and WAT

TBA

## Task 4: Use the CDXJ index to extract a subset of raw content from the local WARC, WET, and WAT 

TBA

## Task 5: Wreck the WARC by compressing it wrong

As mentioned earlier, WARC/WET/WAT files look like they're gzipped, but they're actually gzipped in a particular way that allows random access. This means that you can't `gunzip` and then `gzip` a warc without wrecking random access. This example:

* creates a copy of one of the warc files in the repo
* uncompresses it
* recompresses it the wrong way
* runs `org.commoncrawl.whirlwind.ReadWARC` over it to show that it triggers an error (in fact in java it does not trigger an error... )
* recompresses it the right way using `org.commoncrawl.whirlwind.RecompressWARC`
* shows that this compressed file works

Run

```make wreck_the_warc```

and read through the output. You should get something like the output below:

<details>
  <summary>Click to view output</summary>

```
we will break and then fix this warc
cp data/whirlwind.warc.gz data/testing.warc.gz
rm -f data/testing.warc
gzip -d data/testing.warc.gz  # windows gunzip no work-a

iterate over this uncompressed warc: works
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc"
 WARC-Type: warcinfo
 WARC-Type: request
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: response
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: metadata
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete

compress it the wrong way
gzip data/testing.warc

iterating over this compressed warc fails
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc.gz" || /usr/bin/true
This file is probably not a multi-member gzip but a single gzip file.
To allow seek, a gzipped WARC must have each record compressed into a single gzip member and concatenated together.

This file is likely still valid and can be fixed by running:
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.RecompressWARC -Dexec.args="testing.warc testing.warc.gz"

now let's do it the right way
gzip -d data/testing.warc.gz
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.RecompressWARC -Dexec.args="data/testing.warc data/testing.warc.gz"

and now iterating works
mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc.gz"
 WARC-Type: warcinfo
 WARC-Type: request
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: response
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
 WARC-Type: metadata
 WARC-Target-URI https://an.wikipedia.org/wiki/Escopete
```

</details>

Make sure you compress WARCs the right way!

## Task 6: Use cdx_toolkit to query the full CDX index and download those captures from AWS S3

TBA 

## Task 7: Find the right part of the columnar index 

Now let's look at the columnar index, the other kind of index that Common Crawl makes available. This index is stored in parquet files so you can access it using SQL-based tools like AWS Athena and duckdb as well as through tables in your favorite table packages such as pandas, pyarrow, and polars.

We could read the data directly from our index in our S3 bucket and analyse it in the cloud through AWS Athena. However, this is a managed service that costs money to use (though usually a small amount). [You can read about using it here.](https://commoncrawl.org/blog/index-to-warc-files-and-urls-in-columnar-format) This whirlwind tour will only use the free method of either fetching data from outside of AWS (which is kind of slow), or making a local copy of a single columnar index (300 gigabytes per monthly crawl), and then using that.

The columnar index is divided up into a separate index per crawl, which Athena or duckdb can stitch together. The cdx index is similarly divided up, but cdx_toolkit hides that detail from you.

For the purposes of this whirlwind tour, we don't want to configure all the crawl indices because it would be slow. So let's start by figuring out which crawl was ongoing on the date 20240518015810, and then we'll work with just that one crawl.

### Downloading collinfo.json

We're going to use the `collinfo.json` file to find out which crawl we want. This file includes the dates for the start and end of every crawl and is available through the Common Crawl website at [index.commoncrawl.org](https://index.commoncrawl.org). To download it, run:

```make download_collinfo```

The output should look like:

<details>
  <summary>Click to view output</summary>

```
downloading collinfo.json so we can find out the crawl name
curl -O https://index.commoncrawl.org/collinfo.json
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100 30950  100 30950    0     0  75467      0 --:--:-- --:--:-- --:--:-- 75487
```

</details>

The date of our test record is 20240518015810, which is
2024-05-18T01:58:10 if you add the delimiters back in. We can scroll through the records in `collinfo.json` and look at the from/to values to find the right crawl: CC-MAIN-2024-22. Now we know the crawl name, we can access the correct fraction of the index without having to read the metadata of all the rest.

## Task 8: Query using the columnar index + DuckDB from outside AWS

TBA

### Bonus: download a full crawl index and query with DuckDB

TBA

## Bonus 2: combine some steps

1. Use the DuckDb techniques from [Task 8](#task-8-query-using-the-columnar-index--duckdb-from-outside-aws) and the [Index Server](https://index.commoncrawl.org) to find a new webpage in the archives. 
2. Note its url, warc, and timestamp. 
3. Now open up the Makefile from [Task 6](#task-6-use-cdx_toolkit-to-query-the-full-cdx-index-and-download-those-captures-from-aws-s3) and look at the actions from the cdx_toolkit section.
4. Repeat the cdx_toolkit steps, but for the page and date range you found above.

## Congratulations!

You have completed the Whirlwind Tour of Common Crawl's Datasets using Python! You should now understand different filetypes we have in our corpus and how to interact with Common Crawl's datasets using Python. To see what other people have done with our data, see the  [Examples page](https://commoncrawl.org/examples) on our website. Why not join our Discord through the Community tab?


## Other datasets

We make more datasets available than just the ones discussed in this Whirlwind Tour. Below is a short introduction to some of these other datasets, along with links to where you can find out more.

### Web Graphs

Common Crawl regularly releases Web Graphs which are graphs describing the structure and connectivity of the web as captured in the crawl releases. We provide two levels of graph: host-level and domain-level. Both are available to download [from our website](https://commoncrawl.org/web-graphs). 

The host-level graph describes links between pages on the web at the level of hostnames (e.g. `en.wikipedia.org`). The domain-level graph aggregates this information in the host-level graph, describing links at the pay-level domain (PLD) level (based on the public suffix list maintained on [publicsuffix.org](publicsuffix.org)). The PLD is the subdomain directly under the top-level domain (TLD): e.g. for `en.wikipedia.org`, the TLD would be `.org` and the PLD would be `wikipedia.org`.

As an example, let's look at the [Web Graph release for March, April and May 2025](https://data.commoncrawl.org/projects/hyperlinkgraph/cc-main-2025-mar-apr-may/index.html). This page provides links to download data associated with the host- and domain-level graph for those months. The key files needed to construct the graphs are the files containing the vertices or nodes (the hosts or domains), and the files containing the edges (the links between the hosts/domains). These are currently the top two links in each of the tables. 

![web-graph.png](img/web-graph.png)

The `.txt` files for nodes and edges are actually tab-separated files. The "Description" column in the table explains what data is in the columns. If we download the domain-level graph vertices, 
[cc-main-2025-mar-apr-may-domain-vertices.txt](https://data.commoncrawl.org/projects/hyperlinkgraph/cc-main-2025-mar-apr-may/domain/cc-main-2025-mar-apr-may-domain-vertices.txt.gz), we find that the top of the file looks like this:

```tsv
0	aaa.1111	1
1	aaa.11111	1
2	aaa.2	1
3	aaa.a	1
4	aaa.aa	1
5	aaa.aaa	3
6	aaa.aaaa	1
7	aaa.aaaaaa	1
8	aaa.aaaaaaa	1
9	aaa.aaaaaaaaa	1
```
The first column gives the node ID, the second gives the (pay-level) domain name (as provided by reverse DNS), and the third column gives the number of hosts in the domain.

We can also look at the top of the domain-level edges/vertices [cc-main-2025-mar-apr-may-domain-edges.txt](https://data.commoncrawl.org/projects/hyperlinkgraph/cc-main-2025-mar-apr-may/domain/cc-main-2025-mar-apr-may-domain-edges.txt.gz):

```tsv
39	126790965
41	53700629
41	126790965
42	126790965
48	22113090
48	91547783
48	110426784
48	119774627
48	121059062
49	22113090
```
Here, each row defines a link between two domains, with the first column giving the ID of the originating nodes, and the second column giving the ID of the destination node. The files of nodes and edges for the host-level graph are similar to those for the domain graph, with the only difference being that there is no column for number of hosts in a domain.

If you're interested in working more with the Web Graphs, we provide a [repository](https://github.com/commoncrawl/cc-webgraph) with tools to construct, process, and explore the Web Graphs. We also have a [notebook](https://github.com/commoncrawl/cc-notebooks/tree/main/cc-webgraph-statistics) which shows users how to view statistics about the Common Crawl Web Graph data sets and interactively explore the graphs.

### Host index

The host index is a database which has one row for every web host we know about in each individual crawl. It contains summary information from the crawl, indices, the web graph, and our raw crawler logs. More information is available [here](https://commoncrawl.org/blog/introducing-the-host-index). We also provide a [repository](https://github.com/commoncrawl/cc-host-index) containing examples on how to use the host index. 

### Index annotations

Index annotations allow users to create a database table that can be joined to Common Crawl's columnar url index or host index. This is useful because we can enrich our datasets with extra information and then use it for analysis. We have a [repository](https://github.com/commoncrawl/cc-index-annotations) with example code for joining annotations to the columnar url index or host index.

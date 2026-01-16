build:
	mvn clean package

cdxj: build jwarc.jar
	@echo "creating *.cdxj index files from the local warcs"
	java -jar jwarc.jar cdxj data/whirlwind.warc.gz > data/whirlwind.warc.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wet.gz --records conversion" > data/whirlwind.warc.wet.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wat.gz --records metadata" > data/whirlwind.warc.wat.cdxj

extract: jwarc.jar
	@echo "creating extraction.* from local warcs, the offset numbers are from the cdxj index"
	java -jar jwarc.jar extract --payload data/whirlwind.warc.gz 1023 > extraction.html
	java -jar jwarc.jar extract --payload data/whirlwind.warc.wet.gz 466 > extraction.txt
	java -jar jwarc.jar extract --payload data/whirlwind.warc.wat.gz 443 > extraction.json
	@echo "hint: python -m json.tool extraction.json"

cdx_toolkit: jwarc.jar
	@echo demonstrate that we have this entry in the index
	curl 'https://index.commoncrawl.org/CC-MAIN-2024-22-index?url=an.wikipedia.org/wiki/Escopete&output=json&from=20240518015810&to=20240518015810'
	@echo
	@echo cleanup previous work
	rm -f TEST-000000.extracted.warc.gz
	@echo retrieve the content from the commoncrawl data server
	curl --request GET --url 'https://data.commoncrawl.org/crawl-data/CC-MAIN-2024-22/segments/1715971057216.39/warc/CC-MAIN-20240517233122-20240518023122-00000.warc.gz' --header 'Range: bytes=80610731-80628153' > TEST-000000.extracted.warc.gz
	@echo
	@echo index this new warc
	java -jar jwarc.jar cdxj TEST-000000.extracted.warc.gz  > TEST-000000.extracted.warc.cdxj
	cat TEST-000000.extracted.warc.cdxj
	@echo
	@echo iterate this new warc
	java -jar jwarc.jar ls TEST-000000.extracted.warc.gz
	@echo

download_collinfo:
	@echo "downloading collinfo.json so we can find out the crawl name"
	curl -o data/collinfo.json https://index.commoncrawl.org/collinfo.json

CC-MAIN-2024-22.warc.paths.gz:
	@echo "downloading the list from s3, requires s3 auth even though it is free"
	@echo "note that this file should be in the repo"
	aws s3 ls s3://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2024-22/subset=warc/ | awk '{print $$4}' | gzip -9 > data/CC-MAIN-2024-22.warc.paths.gz

duck_ccf_local_files: build
	@echo "warning! only works on Common Crawl Foundadtion's development machine"
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.Duck -Dexec.args"ccf_local_files"

duck_cloudfront: build
	@echo "warning! this might take 1-10 minutes"
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.Duck -Dexec.args"cloudfront"

jwarc.jar:
	@echo "downloading JWarc JAR"
	curl -fL -o jwarc.jar https://github.com/iipc/jwarc/releases/download/v0.33.0/jwarc-0.33.0.jar

wreck_the_warc: build jwarc.jar
	@echo
	@echo we will break and then fix this warc
	cp data/whirlwind.warc.gz data/testing.warc.gz
	rm -f data/testing.warc
	gzip -d data/testing.warc.gz  # windows gunzip no work-a
	@echo
	@echo compress it the wrong way
	gzip data/testing.warc
	@echo
	@echo showing the records in the compressed warc - note the offsets of request and response are
	java -jar jwarc.jar ls data/testing.warc.gz
	@echo
	@echo access the request record - failing
	java -jar jwarc.jar extract data/testing.warc.gz 3734 || /usr/bin/true
	@echo
	@echo access the response record - failing
	java -jar jwarc.jar extract data/testing.warc.gz 3734 || /usr/bin/true
	@echo
	@echo "now let's do it the right way"
	gzip -d data/testing.warc.gz
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.RecompressWARC -Dexec.args="data/testing.warc data/testing.warc.gz"
	@echo
	@echo showing the records in the compressed warc - note the skewed offsets of request and response
	java -jar jwarc.jar ls data/testing.warc.gz
	@echo
	@echo access the request record - works
	java -jar jwarc.jar extract data/testing.warc.gz 518 | head
	@echo
	@echo access the response record - works
	java -jar jwarc.jar extract data/testing.warc.gz 1027 | head -n 20
	@echo

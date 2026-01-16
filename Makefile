build:
	mvn clean package

cdxj: build ensure_jwarc
	@echo "creating *.cdxj index files from the local warcs"
	java -jar jwarc.jar cdxj data/whirlwind.warc.gz > data/whirlwind.warc.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wet.gz --records conversion" > data/whirlwind.warc.wet.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wat.gz --records metadata" > data/whirlwind.warc.wat.cdxj

extract:
	@echo "creating extraction.* from local warcs, the offset numbers are from the cdxj index"
	java -jar jwarc.jar extract --payload data/whirlwind.warc.gz 1023 > extraction.html
	java -jar jwarc.jar extract --payload data/whirlwind.warc.wet.gz 466 > extraction.txt
	java -jar jwarc.jar extract --payload data/whirlwind.warc.wat.gz 443 > extraction.json
	@echo "hint: python -m json.tool extraction.json"

# cdx_toolkit:
# 	@echo demonstrate that we have this entry in the index
# 	cdxt --crawl CC-MAIN-2024-22 --from 20240518015810 --to 20240518015810 iter an.wikipedia.org/wiki/Escopete
# 	@echo
# 	@echo cleanup previous work
# 	rm -f TEST-000000.extracted.warc.gz
# 	@echo retrieve the content from the commoncrawl s3 bucket
# 	cdxt --crawl CC-MAIN-2024-22 --from 20240518015810 --to 20240518015810 warc an.wikipedia.org/wiki/Escopete
# 	@echo
# 	@echo index this new warc
# 	cdxj-indexer TEST-000000.extracted.warc.gz  > TEST-000000.extracted.warc.cdxj
# 	cat TEST-000000.extracted.warc.cdxj
# 	@echo
# 	@echo iterate this new warc
# 	python ./warcio-iterator.py TEST-000000.extracted.warc.gz
# 	@echo
#
download_collinfo:
	@echo "downloading collinfo.json so we can find out the crawl name"
	curl -o data/collinfo.json https://index.commoncrawl.org/collinfo.json

CC-MAIN-2024-22.warc.paths.gz:
	@echo "downloading the list from s3, requires s3 auth even though it is free"
	@echo "note that this file should be in the repo"
	 aws s3 ls s3://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2024-22/subset=warc/ | awk '{print $$4}' | gzip -9 > data/CC-MAIN-2024-22.warc.paths.gz

# duck_local_files:
# 	@echo "warning! 300 gigabyte download"
# 	python duck.py local_files
#
# duck_ccf_local_files:
# 	@echo "warning! only works on Common Crawl Foundadtion's development machine"
# 	python duck.py ccf_local_files
#
# duck_cloudfront:
# 	@echo "warning! this might take 1-10 minutes"
# 	python duck.py cloudfront
#

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

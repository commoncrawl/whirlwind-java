build:
	mvn clean package

cdxj: build ensure_jwarc
	@echo "creating *.cdxj index files from the local warcs"
	java -jar jwarc.jar cdxj data/whirlwind.warc.gz > whirlwind.warc.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wet.gz --records conversion" > whirlwind.warc.wet.cdxj
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.CdxjIndexer -Dexec.args="data/whirlwind.warc.wat.gz --records metadata" > whirlwind.warc.wat.cdxj

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
	curl -O https://index.commoncrawl.org/collinfo.json

CC-MAIN-2024-22.warc.paths.gz:
	@echo "downloading the list from s3, requires s3 auth even though it is free"
	@echo "note that this file should be in the repo"
	 aws s3 ls s3://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2024-22/subset=warc/ | awk '{print $$4}' | gzip -9 > CC-MAIN-2024-22.warc.paths.gz

duck_ccf_local_files: build
	@echo "warning! only works on Common Crawl Foundadtion's development machine"
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.Duck -Dexec.args"ccf_local_files"

duck_cloudfront: build
	@echo "warning! this might take 1-10 minutes"
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.Duck -Dexec.args"cloudfront"


ensure_jwarc:
	@echo "Ensuring JWarc JAR is present"
	@if [ ! -f jwarc.jar ] ; then \
	  echo "jwarc.jar not found, downloading..." ; \
	  curl -fL -o jwarc.jar https://github.com/iipc/jwarc/releases/download/v0.33.0/jwarc-0.33.0.jar ; \
	else \
	  echo "jwarc.jar found." ; \
	fi

get_jwarc:
	@echo "downloading JWarc JAR"
	curl -fL -o jwarc.jar https://github.com/iipc/jwarc/releases/download/v0.33.0/jwarc-0.33.0.jar

wreck_the_warc: build ensure_jwarc
	@echo
	@echo we will break and then fix this warc
	cp data/whirlwind.warc.gz data/testing.warc.gz
	rm -f data/testing.warc
	gzip -d data/testing.warc.gz  # windows gunzip no work-a
	@echo
	@echo iterate over this uncompressed warc: works
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc"
	@echo
	@echo compress it the wrong way
	gzip data/testing.warc
	@echo
	@echo iterating over this compressed warc fails
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc.gz" || /usr/bin/true
	@echo
	@echo "now let's do it the right way"
	gzip -d data/testing.warc.gz
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.RecompressWARC -Dexec.args="data/testing.warc data/testing.warc.gz"
	@echo
	@echo and now iterating works
	mvn -q exec:java -Dexec.mainClass=org.commoncrawl.whirlwind.ReadWARC -Dexec.args="data/testing.warc.gz"
	@echo

MAKEFILE_DIR := $(dir $(lastword $(MAKEFILE_LIST)))
include $(MAKEFILE_DIR)/standard_defs.mk

build: $(MARKERS)/build_mvn

package: $(MARKERS)/package_docker

test: $(MARKERS)/test_mvn

analyze: analyze_sonar_mvn

clean: clean_mvn

distclean: clean_docker

publish: $(MARKERS)/publish_mvn

$(MARKERS)/package_docker: $(MARKERS)/package_mvn
	docker-compose -f docker-compose.yaml build

.PHONY: clean_container
clean_container:
	docker-compose -f docker-compose.yaml rm -f || true
	docker-compose -f docker-compose.yaml down -v || true

.PHONY: clean_docker
clean_docker:
	docker-compose -f docker-compose.yaml rm -f || true
	docker-compose -f docker-compose.yaml down -v --rmi all || true

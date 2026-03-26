# SAM Java multi-module build — targets must match AWS::Serverless::Function logical IDs.
ARTIFACTS_DIR ?= .
MVN := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))/mvnw

.PHONY: build-NbaIngestionFunctionV2 build-PpLineIngestionFunction build-ScoringFunction build-OutcomeRecorderFunction build-ApiFunction

build-NbaIngestionFunctionV2:
	"$(MVN)" -B -q -pl lambda/nba-ingestion -am package -DskipTests
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp lambda/nba-ingestion/target/nba-ingestion-1.0.0-SNAPSHOT.jar "$(ARTIFACTS_DIR)/lib/"

build-PpLineIngestionFunction:
	"$(MVN)" -B -q -pl lambda/pp-line-ingestion -am package -DskipTests
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp lambda/pp-line-ingestion/target/pp-line-ingestion-1.0.0-SNAPSHOT.jar "$(ARTIFACTS_DIR)/lib/"

build-ScoringFunction:
	"$(MVN)" -B -q -pl lambda/scoring -am package -DskipTests
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp lambda/scoring/target/scoring-1.0.0-SNAPSHOT.jar "$(ARTIFACTS_DIR)/lib/"

build-OutcomeRecorderFunction:
	"$(MVN)" -B -q -pl lambda/outcome-recorder -am package -DskipTests
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp lambda/outcome-recorder/target/outcome-recorder-1.0.0-SNAPSHOT.jar "$(ARTIFACTS_DIR)/lib/"

build-ApiFunction:
	"$(MVN)" -B -q -pl lambda/api -am package -DskipTests
	mkdir -p "$(ARTIFACTS_DIR)/lib"
	cp lambda/api/target/api-1.0.0-SNAPSHOT.jar "$(ARTIFACTS_DIR)/lib/"

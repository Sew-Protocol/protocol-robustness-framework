.PHONY: reference-validation-v1
reference-validation-v1:
	./suites/reference-validation-v1/scripts/run.sh

.PHONY: speds-check
speds-check:
	clojure scripts/speds_consistency_check.clj

.PHONY: speds-issues
speds-issues:
	clojure scripts/speds_generate_issues.clj

.PHONY: speds-comparator-shadow
speds-comparator-shadow:
	clojure -M:speds-comparator-shadow

.PHONY: speds-findings
speds-findings:
	clojure scripts/speds_generate_issues.clj

.PHONY: speds-artifacts
speds-artifacts:
	$(MAKE) speds-findings
	$(MAKE) speds-issues
	$(MAKE) speds-check

.PHONY: semantic-registry-generate
semantic-registry-generate:
	clojure -M:test -m resolver-sim.tools.generate-semantic-vocab

.PHONY: semantic-registry-check
semantic-registry-check:
	clojure -M:test -m resolver-sim.tools.generate-semantic-vocab --check

.PHONY: semantic-phase3-check
semantic-phase3-check:
	$(MAKE) semantic-registry-check
	clojure -M:test -e "(require 'resolver-sim.notebooks.speds.findings-comparator-test 'resolver-sim.notebooks.speds.issues-shadow-report-test 'resolver-sim.evidence.semantic-facts-test 'resolver-sim.definitions.registry-test) (clojure.test/run-tests 'resolver-sim.notebooks.speds.findings-comparator-test 'resolver-sim.notebooks.speds.issues-shadow-report-test 'resolver-sim.evidence.semantic-facts-test 'resolver-sim.definitions.registry-test)" 2>&1

.PHONY: state-machine-docs-generate
state-machine-docs-generate:
	clojure scripts/generate_state_machine_docs.clj --protocol sew

.PHONY: state-machine-docs-check
state-machine-docs-check:
	clojure scripts/generate_state_machine_docs.clj --protocol sew --check

.PHONY: protocol-sew-docs-generate
protocol-sew-docs-generate:
	clojure scripts/generate_state_machine_docs.clj --protocol sew

.PHONY: protocol-sew-docs-check
protocol-sew-docs-check:
	clojure scripts/generate_state_machine_docs.clj --protocol sew --check

.PHONY: docs-as-code-check
docs-as-code-check:
	$(MAKE) semantic-registry-check
	$(MAKE) core-generated-docs-check
	$(MAKE) state-machine-docs-check

.PHONY: core-generated-docs-generate
core-generated-docs-generate:
	clojure scripts/generate_core_docs.clj

.PHONY: core-generated-docs-check
core-generated-docs-check:
	clojure scripts/generate_core_docs.clj --check

.PHONY: docs-as-code-check-framework
docs-as-code-check-framework:
	$(MAKE) semantic-registry-check
	$(MAKE) core-generated-docs-check

.PHONY: docs-as-code-check-sew
docs-as-code-check-sew:
	$(MAKE) semantic-registry-check
	$(MAKE) protocol-sew-docs-check

.PHONY: verify-reference-validation-v1
verify-reference-validation-v1:
	./suites/reference-validation-v1/scripts/verify.sh

.PHONY: clean-reference-validation-v1
clean-reference-validation-v1:
	./suites/reference-validation-v1/scripts/clean.sh

.PHONY: report-reference-validation-v1
report-reference-validation-v1:
	./suites/reference-validation-v1/scripts/generate-report.sh

.PHONY: reference-validation-v1-check
reference-validation-v1-check:
	$(MAKE) clean-reference-validation-v1
	$(MAKE) reference-validation-v1
	$(MAKE) verify-reference-validation-v1
	$(MAKE) report-reference-validation-v1

.PHONY: refresh-reference-validation-v1
refresh-reference-validation-v1:
	clojure -M:reference-validation --refresh-expected

.PHONY: sew-domain-reference-v1
sew-domain-reference-v1:
	./suites/sew-domain-reference-v1/scripts/run.sh

.PHONY: verify-sew-domain-reference-v1
verify-sew-domain-reference-v1:
	./suites/sew-domain-reference-v1/scripts/verify.sh

.PHONY: clean-sew-domain-reference-v1
clean-sew-domain-reference-v1:
	./suites/sew-domain-reference-v1/scripts/clean.sh

.PHONY: report-sew-domain-reference-v1
report-sew-domain-reference-v1:
	./suites/sew-domain-reference-v1/scripts/generate-report.sh

.PHONY: sew-domain-reference-v1-check
sew-domain-reference-v1-check:
	$(MAKE) clean-sew-domain-reference-v1
	$(MAKE) sew-domain-reference-v1
	$(MAKE) verify-sew-domain-reference-v1
	$(MAKE) report-sew-domain-reference-v1

.PHONY: refresh-sew-domain-reference-v1
refresh-sew-domain-reference-v1:
	clojure -M:reference-validation --suite-root suites/sew-domain-reference-v1 --protocol sew --refresh-expected

.PHONY: yield-reference-v1
yield-reference-v1:
	./suites/yield-reference-v1/scripts/run.sh

.PHONY: verify-yield-reference-v1
verify-yield-reference-v1:
	./suites/yield-reference-v1/scripts/verify.sh

.PHONY: clean-yield-reference-v1
clean-yield-reference-v1:
	./suites/yield-reference-v1/scripts/clean.sh

.PHONY: report-yield-reference-v1
report-yield-reference-v1:
	./suites/yield-reference-v1/scripts/generate-report.sh

.PHONY: yield-reference-v1-check
yield-reference-v1-check:
	$(MAKE) clean-yield-reference-v1
	$(MAKE) yield-reference-v1
	$(MAKE) verify-yield-reference-v1
	$(MAKE) report-yield-reference-v1

.PHONY: refresh-yield-reference-v1
refresh-yield-reference-v1:
	clojure -M:reference-validation --suite-root suites/yield-reference-v1 --protocol yield --refresh-expected

# ---------------------------------------------------------------------------
# XTDB
# ---------------------------------------------------------------------------

.PHONY: xtdb
xtdb:
	docker compose up -d xtdb

.PHONY: xtdb-stop
xtdb-stop:
	docker compose stop xtdb

.PHONY: xtdb-down
xtdb-down:
	docker compose down xtdb

.PHONY: xtdb-logs
xtdb-logs:
	docker compose logs xtdb

.PHONY: db-setup
db-setup: xtdb
	bb db:setup

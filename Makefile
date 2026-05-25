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
	clojure scripts/generate_state_machine_docs.clj

.PHONY: state-machine-docs-check
state-machine-docs-check:
	clojure scripts/generate_state_machine_docs.clj --check

.PHONY: docs-as-code-check
docs-as-code-check:
	$(MAKE) semantic-registry-check
	$(MAKE) state-machine-docs-check

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

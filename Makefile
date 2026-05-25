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

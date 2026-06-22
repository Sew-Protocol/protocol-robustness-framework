# Historical Note

Prior to Canonical Hash Specification V1 the framework used an implementation-defined hashing mechanism based on:

* recursive map sorting
* Clojure EDN serialization via pr-str
* SHA-256 over UTF-8 encoded printer output

This mechanism was never published as a protocol specification and was used only during internal development.

Artifacts generated before Canonical Hash Specification V1 are considered pre-standard artifacts and are outside the scope of interoperability guarantees.

Canonical Hash Specification V1 is the first normative hashing specification of the framework.


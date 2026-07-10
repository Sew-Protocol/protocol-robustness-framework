Minimal guide to good DAG design in 2026
1. Give every edge one precise meaning

Do not treat every hash reference as the same kind of graph edge.

At minimum distinguish:

:dag/parent             ;; determines graph topology
:reference/input        ;; semantic dependency
:reference/output       ;; generated or committed artifact
:reference/bootstrap    ;; external trust or chain anchor
:attestation/subject    ;; claim about another node
:selection/support      ;; evidence supporting selection

A structural parent edge should answer:

What prior DAG node must exist for this node to exist?

A semantic reference should answer:

What external or internal object does this node mention or commit to?

This separation is one of the most important design choices in the proposed architecture.

2. Make canonical nodes immutable and content-addressed

The node identifier should derive from its canonical logical content. Changing meaningful content creates a different node rather than mutating the existing one.

This gives you natural deduplication, tamper detection and historical version links. IPLD similarly treats content-addressed data as immutable by default and constructs DAGs by linking immutable objects.

node-hash = SHA-256(canonical-node-content)

Never use a database row ID, path or insertion order as canonical identity.

3. Define the hash preimage explicitly

Document exactly which fields enter the hash:

{:hash/includes
 [:node/type
  :node/schema-version
  :canonical/version
  :parent-hashes
  :bootstrap-roots
  :inputs
  :outputs
  :status]

 :hash/excludes
 [:artifact/path
  :display/label
  :observation/created-at
  :ui/position]}

Canonical serialisation must be deterministic across runtimes. RFC 8785 exists specifically to produce invariant JSON representations suitable for repeatable hashing and signing.

Maintain cross-language canonicalisation vectors, not only same-runtime unit tests.

4. Enforce acyclicity by construction

The safest rule is:

A node may reference as a structural parent only a node that has already been finalized.

That prevents most cycles without needing a global graph traversal during every write.

Still run a full verifier that checks:

no self-parent;
no repeated parent;
no path from a parent back to the child;
all required parents resolve;
referenced parent hash matches canonical content.

Post-hoc aggregates and commitment nodes are particularly useful because they can point backwards without forcing earlier nodes to point forwards.

5. Distinguish graph roots from verification entry points

Name these independently:

Graph root
    A node with no structural parents.

Commitment root
    A post-hoc commitment that binds the run outputs.

Verification entry point
    The hash a verifier is expected to start from.

External anchor
    A hash or signature outside the evidence-node DAG.

They may occasionally be the same object, but the schema should not assume that they are.

For your architecture:

execution node        = graph genesis/root
commitment root       = advertised verification entry point
evidence cursor       = external/bootstrap anchor
selection certificate = higher-level verification entry point
6. Keep nodes small; reference large artifacts

A DAG node should contain enough information to:

establish identity;
state its type;
describe its relationships;
verify its commitments;
locate or resolve referenced content.

Large scenario outputs, notebooks, traces and evidence records should normally be separate content-addressed artifacts.

:outputs
{:result/hash "sha256:..."
 :result/media-type "application/prf-result+json"
 :result/byte-size 428113}

Avoid embedding a large artifact and referencing the same artifact elsewhere. Choose one canonical representation.

7. Version every interpretation boundary

Include:

:node/schema-version
:canonical/version
:hash/algorithm
:reference/schema-version
:policy/version

A verifier should never need to guess how a historical node was interpreted.

Schema upgrades should generally create new nodes:

old-node ──derived-as──> upgraded-node

Do not silently reinterpret an existing content hash under a new schema.

8. Use typed hash references

Avoid unqualified values such as:

"af82c9..."

At minimum use:

sha256:af82c9...

For external references, use either a strict tagged string:

evidence-chain:sha256:af82c9...

or preferably a structured reference:

{:reference/type :evidence-chain
 :hash/algorithm :sha256
 :hash/value "af82c9..."}

Structured references are easier to validate and migrate; compact strings are convenient for storage and display.

9. Separate evidence, attestation and selection

Do not combine these into one node:

Evidence node
    What occurred or was computed.

Attestation
    What a particular runner signs about that evidence.

Selection certificate
    Which competing result was selected and under what rule.

Consensus certificate
    Whether the required support threshold was met.

The in-toto attestation model follows a similar separation by binding an attestation statement to identified subjects and a typed predicate.

This allows evidence to remain stable while runners, signatures and selection policies evolve.

10. Model provenance explicitly

For each result, be able to answer:

What inputs were used?
What activity produced it?
Who or what performed that activity?
Which policy governed it?
What did it generate?

W3C PROV formalises the same core separation between entities, activities and responsible agents.

You do not need to serialize PRF nodes as PROV-O, but keeping that conceptual distinction will improve interoperability.

11. Make incomplete and disputed graphs first-class

Real distributed evidence graphs will contain:

missing nodes;
unavailable artifacts;
invalid signatures;
competing commitment roots;
attestations for different results;
revoked runners;
unresolved selection;
partially verified subgraphs.

Do not encode all of this as :status :fail.

Prefer explicit states:

:resolution/status
;; :resolved
;; :missing
;; :malformed
;; :hash-mismatch
;; :unauthorized
;; :unsupported-schema

:selection/status
;; :selected
;; :disputed
;; :insufficient-quorum
;; :not-evaluated
12. Design traversal and verification together

Every reference type should define:

{:reference/type :evidence-chain
 :required? true
 :resolver-method :resolve-evidence-chain
 :verification-method :verify-evidence-chain
 :traversal/default? true}

The resolver should support bounded traversal:

(resolve-subgraph resolver entry-hash
                  {:direction :ancestors
                   :max-depth 20
                   :max-nodes 10000
                   :include-reference-types
                   #{:dag/parent
                     :attestation/subject}})

This prevents malformed or adversarial graphs from causing uncontrolled traversal.

A compact review checklist

A well-designed evidence DAG should pass these questions:

□ Can every canonical node be independently re-hashed?
□ Are structural edges distinct from semantic references?
□ Can a cycle be rejected before or during persistence?
□ Is there an unambiguous verification entry point?
□ Are hashes typed and algorithms explicit?
□ Are wall-clock and UI fields excluded from identity?
□ Are large artifacts referenced rather than duplicated?
□ Are schema and canonicalisation versions pinned?
□ Can every reference be resolved or explicitly marked missing?
□ Are evidence, attestations and selection separate?
□ Can disagreement exist without corrupting prior evidence?
□ Can the complete DAG be rebuilt from canonical artifacts?
□ Is every database or GUI merely a replaceable projection?

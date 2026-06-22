Canonical Hash Specification V1
Status
Draft
Purpose
This specification defines the canonical serialization and hashing algorithm used by the Protocol Robustness Framework.
The goals are:
    • Deterministic hashing across implementations and programming languages
    • Independent verification by third parties
    • Solidity-compatible verification
    • TSA-compatible attestations
    • Long-term forensic reproducibility
This specification supersedes implementation-defined hashing based on Clojure EDN printers, JSON serializers, or language-specific object representations.

1. Terminology
Canonical Bytes
A deterministic byte sequence produced from a value according to this specification.
Two logically equivalent values MUST produce identical canonical bytes.
Content Hash
The SHA-256 digest of a domain-separated canonical byte sequence.
Domain Tag
A fixed ASCII identifier prepended to canonical bytes before hashing.
Domain tags prevent cross-domain hash collisions.

2. Hash Algorithm
All hashes SHALL use SHA-256.
Content hashes SHALL be computed as:
HASH = SHA256(
DOMAIN_TAG || CANONICAL_BYTES
)
Where:
    • DOMAIN_TAG is UTF-8 encoded ASCII
    • CANONICAL_BYTES are produced by this specification
    • || denotes byte concatenation

3. Supported Types
Implementations MUST support only the following value types.
Null
Represents absence of a value.
Boolean
Values:
    • true
    • false
String
UTF-8 encoded Unicode string.
Keyword
Qualified or unqualified symbolic identifier.
Examples:
    • :resolver/id
    • :active
Keywords are distinct from strings.
The keyword:
:active
MUST NOT hash identically to:
"active"
Integer
Arbitrary precision signed integer.
Implementations MUST support values larger than 64-bit range.
Vector
Ordered sequence of values.
Ordering is significant.
Map
Collection of key-value pairs.
Map ordering is not significant.
Keys MUST be:
    • keyword
    • string
No other key types are permitted.
Instant
UTC timestamp represented as an ISO-8601 string.
Example:
2026-06-22T12:34:56.123Z
Implementations MUST normalize to UTC before hashing.

4. Unsupported Types
The following types are prohibited:
    • ratio
    • symbol
    • set
    • list
    • bigint wrapper types
    • bigdec
    • record
    • function
    • atom
    • promise
    • future
    • delay
    • arbitrary Java objects
Attempting to hash an unsupported type MUST fail.
Implementations MUST NOT silently coerce unsupported types.

5. Record Handling
Language-specific record types are prohibited in canonical hashing.
Examples include:
    • Clojure defrecord
    • Java record
    • Rust struct wrappers
    • Python dataclasses
Values must be converted into supported canonical forms before hashing.
Recommended conversion:
{
:type "...",
...
}
or
{
"type": "...",
...
}
Implementations MUST reject raw records.

6. Canonical Encoding
Each value is encoded as a typed binary sequence.
Null
Encoding:
N
ASCII byte:
0x4E

Boolean
true:
T
false:
F

String
Encoding:
S:
Example:
"abc"
encodes as:
S3:abc
Length is the number of UTF-8 bytes.

Keyword
Encoding:
K:
Example:
:resolver/id
encodes as:
K11:resolver/id
Namespace separators are preserved.

Integer
Encoding:
I:
Examples:
0
I1:0
123
I3:123
-42
I3:-42
No leading zeros permitted.

Vector
Encoding:
V[item1][item2]...[itemN]
Example:
["a" "b"]
V2S1:aS1:b
Ordering is preserved.

Map
Maps are encoded in canonical key order.
Canonical key order is:
    1. keywords
    2. strings
Within each category:
UTF-8 lexicographic ordering of the canonical key representation.
Encoding:
M[key1][value1]...[keyN][valueN]
Example:
{
:a 1
:b 2
}
M2K1:aI1:1K1:bI1:2

Instant
Encoding:
TS:
Example:
2026-06-22T12:34:56Z
TS20:2026-06-22T12:34:56Z

7. Canonical Equality
Values are equal only when:
    • type is identical
    • encoded representation is identical
Examples:
:active != "active"
1 != "1"
true != "true"

8. Domain Tags
The following domain tags are reserved.
WORLD_STATE_V1
Hash of a canonicalized world state.
EVIDENCE_RECORD_V1
Hash of a finalized evidence record.
EVIDENCE_CHAIN_V1
Hash of chain metadata.
EVIDENCE_MERKLE_LEAF_V1
Merkle leaf hashing.
EVIDENCE_MERKLE_NODE_V1
Merkle internal node hashing.
REGISTRY_V1
Evidence registry commitment.
MANIFEST_V1
Manifest commitment.
PROVENANCE_V1
Provenance commitment.
BUNDLE_ROOT_V1
Top-level benchmark commitment.
Implementations MAY define additional tags.
Tags MUST be globally unique.

9. Compliance Requirements
A compliant implementation MUST:
    • Produce identical canonical bytes for identical values
    • Reject unsupported types
    • Preserve keyword/string distinction
    • Use SHA-256
    • Use domain-separated hashing
    • Implement canonical map ordering exactly
A compliant implementation MUST NOT:
    • Depend on object identity
    • Depend on language printer output
    • Depend on locale settings
    • Depend on JVM implementation details
    • Depend on JSON serializer behavior

10. Test Vectors
A conformance suite SHALL be maintained.
Each test vector SHALL specify:
    • input value
    • canonical bytes (hex)
    • domain tag
    • expected SHA-256 digest
All implementations MUST pass the published test vectors.


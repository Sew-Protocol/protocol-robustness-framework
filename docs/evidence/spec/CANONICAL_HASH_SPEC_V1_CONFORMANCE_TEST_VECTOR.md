Canonical Hash V1 — Conformance Test Vector Specification
1. Purpose
This specification defines:
    • A deterministic method for producing Canonical Hash V1 
    • A standard format for test vectors 
    • Rules for concatenation in canonical byte construction 
    • Edge cases that must be validated for conformance 
It is designed to ensure interoperability, reproducibility, and audit-grade determinism.

2. Canonical Hash Definition
Canonical Hash V1 is defined as:
HASH_V1 = SHA-256(DOMAIN_TAG || CANONICAL_BYTES)
Where:
    • SHA-256 is the cryptographic hash function 
    • DOMAIN_TAG is a fixed UTF-8 byte sequence identifying the hash domain 
    • || denotes byte-level concatenation (NOT string concatenation) 
    • CANONICAL_BYTES is a deterministic serialization of structured inputs 

3. Core Requirement: Concatenation Semantics
3.1 Byte-Level Concatenation (Mandatory)
All concatenation MUST be:
    • Byte concatenation only 
    • No implicit encoding conversion during concatenation 
    • No delimiter insertion unless explicitly specified 
Formally:
A || B := bytes(A) + bytes(B)

3.2 Concatenation Rules by Type
Type
Rule
Strings
UTF-8 encoding before concatenation
Integers
Big-endian fixed-width encoding OR varint (must be specified per test vector set)
Lists
Concatenation of each element’s canonical encoding
Maps
Sorted by key (lexicographically) then concatenated
Nested structures
Recursively canonicalized then concatenated

3.3 Field Boundary Ambiguity Rule
To avoid ambiguity:
    • No separators are used unless explicitly defined 
    • Structural boundaries must be encoded via: 
        ◦ length-prefix encoding OR 
        ◦ typed canonical serialization rules 

4. Canonical Bytes Construction
4.1 Base Rule
CANONICAL_BYTES = CanonicalEncode(structure)
Where CanonicalEncode:
    1. Normalizes structure (sorting, typing, normalization) 
    2. Encodes primitives 
    3. Concatenates fields deterministically 

4.2 Deterministic Encoding Rules
Strings
STRING := UTF8(value)
Integers
INT := big-endian signed 64-bit (default unless overridden)
Booleans
true  -> 0x01
false -> 0x00
Null
NULL -> 0x00

4.3 Map Encoding
Maps MUST follow:
sort keys lexicographically (byte order)
for each key:
    encode(key) || encode(value)
Then concatenate all pairs:
MAP := (k1||v1)||(k2||v2)||...

4.4 List Encoding
LIST := encode(e1) || encode(e2) || ... || encode(en)
No separators.

5. Domain Separation
5.1 DOMAIN_TAG Rules
DOMAIN_TAG must:
    • Be UTF-8 bytes 
    • Be immutable per spec version 
    • Prevent cross-protocol hash collisions 
Example:
DOMAIN_TAG = "CANONICAL_HASH_V1"
Encoded as:
UTF8("CANONICAL_HASH_V1")

6. Full Hash Construction
HASH_V1 =
SHA256(
    UTF8("CANONICAL_HASH_V1")
    ||
    CanonicalEncode(input)
)

7. Conformance Test Vector Specification
Each test vector MUST include:
7.1 Required Fields
{
  "id": "string",
  "description": "string",
  "input": "structured object",
  "canonical_bytes_hex": "hex string",
  "domain_tag_hex": "hex string",
  "hash_hex": "hex string"
}

7.2 Derived Fields
    • canonical_bytes_hex = hex(CANONICAL_BYTES) 
    • hash_hex = hex(SHA256(DOMAIN_TAG || CANONICAL_BYTES)) 

8. Test Vector Categories
8.1 Primitive Tests
    • single string 
    • single integer 
    • boolean/null values 
8.2 Structural Tests
    • nested maps 
    • mixed-type lists 
    • deep nesting (≥5 levels) 
8.3 Ordering Tests
    • map key ordering independence 
    • list ordering sensitivity 
8.4 Concatenation Stress Tests
    • long strings (>10k bytes) 
    • many-element lists (>10k items) 
    • deeply nested concatenation chains 
8.5 Boundary Ambiguity Tests
Ensures no collisions between:
    • "ab" + "c" vs "a" + "bc" 
    • nested encoding boundaries 

9. Critical Concatenation Edge Cases
9.1 No Delimiter Rule
This is a MUST:
Concatenation must never rely on separators such as |, ,, or whitespace.
Reason:
    • Prevents ambiguity attacks 
    • Ensures cryptographic determinism 

9.2 Length Ambiguity Protection
To avoid collisions:
encode("a") || encode("bc")
≠
encode("ab") || encode("c")
Must be resolved via:
    • length-prefix encoding OR 
    • typed encoding rules 

9.3 Mixed-Type Concatenation
Example:
["1", 1]
Must NOT be ambiguous.
Solution:
    • type-tag each primitive before encoding: 
STRING:1 || INT:1
Encoded as bytes, not text.

10. Cross-System Concatenation Applicability
Concatenation rules here also apply to:
10.1 Evidence Chains
    • chaining attestations 
    • linking prior hashes 
10.2 Merkle-Like Structures (Non-tree variant)
    • linear hash accumulation 
    • append-only logs 
10.3 Pro-rata / Settlement Systems
    • deterministic aggregation of allocations 
    • ordered sum construction inputs 
10.4 Event Sourcing / Audit Logs
    • event serialization 
    • append-only canonical event stream 
10.5 API Signature Schemes
    • request canonicalization 
    • header + body concatenation 

11. Non-Normative Reference Pattern
A safe canonical encoding pipeline:
normalize(input)
→ sort_if_map
→ encode_primitives
→ recursively_encode
→ byte_concatenate
→ prepend_domain_tag
→ sha256

12. Compliance Requirements
A system is compliant if:
    • Hash output matches all provided test vectors 
    • Byte-level concatenation is deterministic 
    • No delimiter-based ambiguity exists 
    • Map ordering is stable and specified 
    • Nested structures are recursively canonicalized


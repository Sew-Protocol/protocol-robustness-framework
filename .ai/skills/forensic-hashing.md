Differentiate semantic artifact hashes from mailbox transport object hashes.

Self-referential hash fields must be excluded before recomputation.

Signature fields must be excluded before signing and verification.

Stable hashes must not include absolute paths, filesystem metadata, wall-clock durations, temp dirs, hostnames, or random UUIDs.

Every new hash helper needs:
  same input same hash
  key order irrelevant
  self-hash field ignored
  mutation changes hash
  relocation does not change hash

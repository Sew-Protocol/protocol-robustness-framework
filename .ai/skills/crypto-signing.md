Use high-level PyNaCl APIs for Ed25519 unless there is a documented reason not to.

Persist Ed25519 private keys as 32-byte seeds, base64 encoded.

Name variables private_seed_b64, not private_key_b64, unless the key format is explicitly documented.

Do not use nacl.bindings.crypto_sign_* directly without tests proving key size assumptions.

Sign canonical message bytes, not local filesystem paths, timestamps, or transport envelopes.

Detached signature = SignedMessage.signature from SigningKey.sign(payload).

Verify with VerifyKey.verify(payload, signature).

When identity registry is provided:
  runner-id -> registry public key
  embedded public key must match registry public key
  signature must verify under registry public key

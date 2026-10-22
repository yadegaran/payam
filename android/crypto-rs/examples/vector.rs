// Prints an identity + signature so server tests can check vodozemac ↔ Node Ed25519 interop.
fn main() {
    let k = [7u8; 32];
    let acc = payamcrypto::new_account(&k).unwrap();
    let (ed, curve) = payamcrypto::identity(&acc, &k).unwrap();
    let sig = payamcrypto::sign(&acc, &k, b"auth:nonce123").unwrap();
    println!("{ed} {curve} {sig}");
}

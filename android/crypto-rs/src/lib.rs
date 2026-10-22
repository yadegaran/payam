//! Thin, stateless bridge to vodozemac's Olm (Double Ratchet) for the Android app.
//! Every call takes an encrypted pickle + 32-byte key and returns the updated
//! pickle, so the Kotlin side just persists strings. Keys travel as standard
//! padded base64 of the raw 32-byte values.
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};

pub type R<T> = Result<T, String>;

fn e<E: std::fmt::Display>(err: E) -> String {
    err.to_string()
}
fn key32(k: &[u8]) -> R<[u8; 32]> {
    k.try_into().map_err(|_| "pickle key must be 32 bytes".to_string())
}
fn curve(b64: &str) -> R<Curve25519PublicKey> {
    Curve25519PublicKey::from_slice(&B64.decode(b64).map_err(e)?).map_err(e)
}
fn load_acc(p: &str, k: &[u8]) -> R<Account> {
    Ok(Account::from_pickle(AccountPickle::from_encrypted(p, &key32(k)?).map_err(e)?))
}
fn save_acc(a: &Account, k: &[u8]) -> R<String> {
    Ok(a.pickle().encrypt(&key32(k)?))
}
fn load_sess(p: &str, k: &[u8]) -> R<Session> {
    Ok(Session::from_pickle(SessionPickle::from_encrypted(p, &key32(k)?).map_err(e)?))
}
fn save_sess(s: &Session, k: &[u8]) -> R<String> {
    Ok(s.pickle().encrypt(&key32(k)?))
}

pub fn new_account(k: &[u8]) -> R<String> {
    save_acc(&Account::new(), k)
}

/// (ed25519, curve25519) public identity keys.
pub fn identity(acc: &str, k: &[u8]) -> R<(String, String)> {
    let ids = load_acc(acc, k)?.identity_keys();
    Ok((B64.encode(ids.ed25519.as_bytes()), B64.encode(ids.curve25519.to_bytes())))
}

pub fn sign(acc: &str, k: &[u8], msg: &[u8]) -> R<String> {
    Ok(B64.encode(load_acc(acc, k)?.sign(msg).to_bytes()))
}

pub fn verify(ed: &str, msg: &[u8], sig: &str) -> bool {
    let check = || -> R<()> {
        let raw: [u8; 32] = B64.decode(ed).map_err(e)?.try_into().map_err(|_| "bad key length")?;
        let key = Ed25519PublicKey::from_slice(&raw).map_err(e)?;
        let sig = Ed25519Signature::from_slice(&B64.decode(sig).map_err(e)?).map_err(e)?;
        key.verify(msg, &sig).map_err(e)
    };
    check().is_ok()
}

/// Generates `n` one-time keys and marks them published. Returns (account, [(id, key)]).
pub fn gen_one_time_keys(acc: &str, k: &[u8], n: usize) -> R<(String, Vec<(String, String)>)> {
    let mut a = load_acc(acc, k)?;
    a.generate_one_time_keys(n);
    let keys = a
        .one_time_keys()
        .into_iter()
        .map(|(id, key)| (id.to_base64(), B64.encode(key.to_bytes())))
        .collect();
    a.mark_keys_as_published();
    Ok((save_acc(&a, k)?, keys))
}

/// Rotates the fallback key (used when the server runs out of one-time keys).
pub fn gen_fallback_key(acc: &str, k: &[u8]) -> R<(String, String, String)> {
    let mut a = load_acc(acc, k)?;
    a.generate_fallback_key();
    let (id, key) = a.fallback_key().into_iter().next().ok_or("no fallback key")?;
    a.mark_keys_as_published();
    Ok((save_acc(&a, k)?, id.to_base64(), B64.encode(key.to_bytes())))
}

pub fn outbound(acc: &str, k: &[u8], their_curve: &str, their_otk: &str) -> R<String> {
    let a = load_acc(acc, k)?;
    let s = a.create_outbound_session(SessionConfig::version_2(), curve(their_curve)?, curve(their_otk)?);
    save_sess(&s, k)
}

fn parse(msg_type: usize, ct: &str) -> R<OlmMessage> {
    OlmMessage::from_parts(msg_type, &B64.decode(ct).map_err(e)?).map_err(e)
}

/// New session from a pre-key message. Returns (account, session, plaintext).
pub fn inbound(acc: &str, k: &[u8], their_curve: &str, ct: &str) -> R<(String, String, Vec<u8>)> {
    let mut a = load_acc(acc, k)?;
    let OlmMessage::PreKey(m) = parse(0, ct)? else { return Err("not a pre-key message".into()) };
    let res = a.create_inbound_session(curve(their_curve)?, &m).map_err(e)?;
    Ok((save_acc(&a, k)?, save_sess(&res.session, k)?, res.plaintext))
}

/// True when this pre-key message belongs to the given existing session.
pub fn session_matches(sess: &str, k: &[u8], ct: &str) -> R<bool> {
    let s = load_sess(sess, k)?;
    match parse(0, ct)? {
        OlmMessage::PreKey(m) => Ok(m.session_id() == s.session_id()),
        _ => Ok(false),
    }
}

/// Returns (session, message type 0|1, ciphertext).
pub fn encrypt(sess: &str, k: &[u8], pt: &[u8]) -> R<(String, usize, String)> {
    let mut s = load_sess(sess, k)?;
    let (t, ct) = s.encrypt(pt).to_parts();
    Ok((save_sess(&s, k)?, t, B64.encode(ct)))
}

pub fn decrypt(sess: &str, k: &[u8], msg_type: usize, ct: &str) -> R<(String, Vec<u8>)> {
    let mut s = load_sess(sess, k)?;
    let pt = s.decrypt(&parse(msg_type, ct)?).map_err(e)?;
    Ok((save_sess(&s, k)?, pt))
}

mod jni_bridge;

#[cfg(test)]
mod tests {
    use super::*;
    const K: [u8; 32] = [7; 32];

    #[test]
    fn olm_roundtrip_and_pickles() {
        let alice = new_account(&K).unwrap();
        let bob = new_account(&K).unwrap();
        let (_, bob_curve) = identity(&bob, &K).unwrap();
        let (_, alice_curve) = identity(&alice, &K).unwrap();
        let (bob, otks) = gen_one_time_keys(&bob, &K, 5).unwrap();
        assert_eq!(otks.len(), 5);

        let a_sess = outbound(&alice, &K, &bob_curve, &otks[0].1).unwrap();
        let (a_sess, t, ct) = encrypt(&a_sess, &K, b"salam").unwrap();
        assert_eq!(t, 0);
        let (_bob, b_sess, pt) = inbound(&bob, &K, &alice_curve, &ct).unwrap();
        assert_eq!(pt, b"salam");
        assert!(session_matches(&b_sess, &K, &ct).unwrap());

        let (b_sess, t, ct) = encrypt(&b_sess, &K, b"javab").unwrap();
        assert_eq!(t, 1);
        let (a_sess, pt) = decrypt(&a_sess, &K, t, &ct).unwrap();
        assert_eq!(pt, b"javab");

        // Replaying an old message must fail; a wrong pickle key must fail.
        assert!(decrypt(&a_sess, &K, t, &ct).is_err());
        assert!(load_sess(&b_sess, &[8; 32]).is_err());
    }

    #[test]
    fn fallback_key_session() {
        let alice = new_account(&K).unwrap();
        let bob = new_account(&K).unwrap();
        let (_, bob_curve) = identity(&bob, &K).unwrap();
        let (_, alice_curve) = identity(&alice, &K).unwrap();
        let (bob, _id, fb) = gen_fallback_key(&bob, &K).unwrap();
        let s = outbound(&alice, &K, &bob_curve, &fb).unwrap();
        let (_, _, ct) = encrypt(&s, &K, b"x").unwrap();
        assert_eq!(inbound(&bob, &K, &alice_curve, &ct).unwrap().2, b"x");
    }

    #[test]
    fn signatures() {
        let a = new_account(&K).unwrap();
        let (ed, _) = identity(&a, &K).unwrap();
        let sig = sign(&a, &K, b"profile").unwrap();
        assert!(verify(&ed, b"profile", &sig));
        assert!(!verify(&ed, b"profile2", &sig));
    }
}

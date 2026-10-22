//! JNI exports for `ir.payam.messenger.core.Olm`. Errors (and panics) become
//! IllegalStateException on the Kotlin side; multi-value results are String[].
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jobjectArray, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use base64::{engine::general_purpose::STANDARD as B64, Engine};

type R<T> = Result<T, String>;

fn s(env: &mut JNIEnv, v: &JString) -> R<String> {
    env.get_string(v).map(Into::into).map_err(|e| e.to_string())
}
fn b(env: &mut JNIEnv, v: &JByteArray) -> R<Vec<u8>> {
    env.convert_byte_array(v).map_err(|e| e.to_string())
}
fn jstr(env: &mut JNIEnv, v: String) -> R<jstring> {
    Ok(env.new_string(v).map_err(|e| e.to_string())?.into_raw())
}
fn arr(env: &mut JNIEnv, items: Vec<String>) -> R<jobjectArray> {
    let a = env.new_object_array(items.len() as i32, "java/lang/String", JString::default()).map_err(|e| e.to_string())?;
    for (i, it) in items.into_iter().enumerate() {
        let js = env.new_string(it).map_err(|e| e.to_string())?;
        env.set_object_array_element(&a, i as i32, js).map_err(|e| e.to_string())?;
    }
    Ok(a.into_raw())
}

fn run<T: Default>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> R<T>) -> T {
    let res = catch_unwind(AssertUnwindSafe(|| f(env))).unwrap_or_else(|_| Err("panic in crypto".into()));
    res.unwrap_or_else(|msg| {
        let _ = env.throw_new("java/lang/IllegalStateException", msg);
        T::default()
    })
}

macro_rules! export {
    ($name:ident ( $env:ident $(, $arg:ident : $ty:ty)* ) -> $ret:ty $body:block) => {
        #[no_mangle]
        pub extern "system" fn $name<'l>(mut $env: JNIEnv<'l>, _c: JClass<'l> $(, $arg: $ty)*) -> $ret {
            run(&mut $env, |$env| $body)
        }
    };
}

export!(Java_ir_payam_messenger_core_Olm_newAccount(env, key: JByteArray<'l>) -> jstring {
    let k = b(env, &key)?;
    jstr(env, crate::new_account(&k)?)
});

export!(Java_ir_payam_messenger_core_Olm_identity(env, acc: JString<'l>, key: JByteArray<'l>) -> jobjectArray {
    let (a, k) = (s(env, &acc)?, b(env, &key)?);
    let (ed, cu) = crate::identity(&a, &k)?;
    arr(env, vec![ed, cu])
});

export!(Java_ir_payam_messenger_core_Olm_sign(env, acc: JString<'l>, key: JByteArray<'l>, msg: JByteArray<'l>) -> jstring {
    let (a, k, m) = (s(env, &acc)?, b(env, &key)?, b(env, &msg)?);
    jstr(env, crate::sign(&a, &k, &m)?)
});

export!(Java_ir_payam_messenger_core_Olm_verify(env, ed: JString<'l>, msg: JByteArray<'l>, sig: JString<'l>) -> jboolean {
    let (e, m, sg) = (s(env, &ed)?, b(env, &msg)?, s(env, &sig)?);
    Ok(crate::verify(&e, &m, &sg) as jboolean)
});

// -> [account, id1, key1, id2, key2, ...]
export!(Java_ir_payam_messenger_core_Olm_genOneTimeKeys(env, acc: JString<'l>, key: JByteArray<'l>, n: jint) -> jobjectArray {
    let (a, k) = (s(env, &acc)?, b(env, &key)?);
    let (a, keys) = crate::gen_one_time_keys(&a, &k, n.max(0) as usize)?;
    let mut out = vec![a];
    for (id, kk) in keys { out.push(id); out.push(kk); }
    arr(env, out)
});

// -> [account, id, key]
export!(Java_ir_payam_messenger_core_Olm_genFallbackKey(env, acc: JString<'l>, key: JByteArray<'l>) -> jobjectArray {
    let (a, k) = (s(env, &acc)?, b(env, &key)?);
    let (a, id, kk) = crate::gen_fallback_key(&a, &k)?;
    arr(env, vec![a, id, kk])
});

export!(Java_ir_payam_messenger_core_Olm_outbound(env, acc: JString<'l>, key: JByteArray<'l>, curve: JString<'l>, otk: JString<'l>) -> jstring {
    let (a, k, c, o) = (s(env, &acc)?, b(env, &key)?, s(env, &curve)?, s(env, &otk)?);
    jstr(env, crate::outbound(&a, &k, &c, &o)?)
});

// -> [account, session, plaintext(base64)]
export!(Java_ir_payam_messenger_core_Olm_inbound(env, acc: JString<'l>, key: JByteArray<'l>, curve: JString<'l>, ct: JString<'l>) -> jobjectArray {
    let (a, k, c, m) = (s(env, &acc)?, b(env, &key)?, s(env, &curve)?, s(env, &ct)?);
    let (a, sess, pt) = crate::inbound(&a, &k, &c, &m)?;
    arr(env, vec![a, sess, B64.encode(pt)])
});

export!(Java_ir_payam_messenger_core_Olm_sessionMatches(env, sess: JString<'l>, key: JByteArray<'l>, ct: JString<'l>) -> jboolean {
    let (se, k, m) = (s(env, &sess)?, b(env, &key)?, s(env, &ct)?);
    Ok(crate::session_matches(&se, &k, &m)? as jboolean)
});

// -> [session, type, ciphertext]
export!(Java_ir_payam_messenger_core_Olm_encrypt(env, sess: JString<'l>, key: JByteArray<'l>, pt: JByteArray<'l>) -> jobjectArray {
    let (se, k, p) = (s(env, &sess)?, b(env, &key)?, b(env, &pt)?);
    let (se, t, ct) = crate::encrypt(&se, &k, &p)?;
    arr(env, vec![se, t.to_string(), ct])
});

// -> [session, plaintext(base64)]
export!(Java_ir_payam_messenger_core_Olm_decrypt(env, sess: JString<'l>, key: JByteArray<'l>, t: jint, ct: JString<'l>) -> jobjectArray {
    let (se, k, m) = (s(env, &sess)?, b(env, &key)?, s(env, &ct)?);
    let (se, pt) = crate::decrypt(&se, &k, t.max(0) as usize, &m)?;
    arr(env, vec![se, B64.encode(pt)])
});

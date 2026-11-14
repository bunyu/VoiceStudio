//! JNI bridge between the reader app and sea-g2p's C interface.
//!
//! Text crosses the boundary as UTF-8 byte arrays: JNI strings use modified UTF-8, which
//! mangles characters outside the Basic Multilingual Plane.

use std::ffi::{CStr, CString};

use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use sea_g2p_rs::capi::{
    sea_g2p_abi_version, sea_g2p_close, sea_g2p_normalize, sea_g2p_open, sea_g2p_phonemize,
    sea_g2p_string_free, SeaG2p,
};

fn text(env: &JNIEnv, bytes: &JByteArray) -> Option<CString> {
    // Interior NULs cannot be spoken; drop them instead of failing the whole passage.
    let mut raw = env.convert_byte_array(bytes).ok()?;
    raw.retain(|b| *b != 0);
    CString::new(raw).ok()
}

/// Copies a string owned by sea-g2p into a Java byte array and frees it. Null stays null.
fn give(env: &JNIEnv, owned: *mut std::ffi::c_char) -> jbyteArray {
    if owned.is_null() {
        return std::ptr::null_mut();
    }
    let out = env
        .byte_array_from_slice(unsafe { CStr::from_ptr(owned) }.to_bytes())
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut());
    unsafe { sea_g2p_string_free(owned) };
    out
}

#[no_mangle]
pub extern "system" fn Java_com_voicestudio_reader_vieneu_SeaG2p_abiVersion(_env: JNIEnv, _class: JClass) -> jint {
    sea_g2p_abi_version()
}

/// Opens the dictionary; 0 when it cannot be read.
#[no_mangle]
pub extern "system" fn Java_com_voicestudio_reader_vieneu_SeaG2p_open(env: JNIEnv, _class: JClass, path: JByteArray) -> jlong {
    match text(&env, &path) {
        Some(path) => unsafe { sea_g2p_open(path.as_ptr()) as jlong },
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_voicestudio_reader_vieneu_SeaG2p_close(_env: JNIEnv, _class: JClass, handle: jlong) {
    unsafe { sea_g2p_close(handle as *mut SeaG2p) }
}

#[no_mangle]
pub extern "system" fn Java_com_voicestudio_reader_vieneu_SeaG2p_phonemize(
    env: JNIEnv, _class: JClass, handle: jlong, input: JByteArray, punc_norm: jboolean,
) -> jbyteArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    match text(&env, &input) {
        Some(input) => give(&env, unsafe { sea_g2p_phonemize(handle as *const SeaG2p, input.as_ptr(), punc_norm as i32) }),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_voicestudio_reader_vieneu_SeaG2p_normalize(
    env: JNIEnv, _class: JClass, handle: jlong, input: JByteArray, punc_norm: jboolean,
) -> jbyteArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    match text(&env, &input) {
        Some(input) => give(&env, unsafe { sea_g2p_normalize(handle as *const SeaG2p, input.as_ptr(), punc_norm as i32) }),
        None => std::ptr::null_mut(),
    }
}

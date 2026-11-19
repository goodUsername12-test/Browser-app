use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::{Mutex, OnceLock};

#[repr(C)]
pub struct JniEnv {
    pub functions: *const JniNativeInterface,
}

/// Exact JNI NativeInterface vtable layout up to slot 170:
///   0..166 : reserved0..3 + JNI functions up to GetStringRegion/GetStringUTFRegion/ReleaseStringCritical
///   167    : NewStringUTF
///   168    : GetStringUTFLength
///   169    : GetStringUTFChars
///   170    : ReleaseStringUTFChars
#[repr(C)]
pub struct JniNativeInterface {
    _reserved: [*const (); 167],
    pub new_string_utf: unsafe extern "C" fn(*mut JniEnv, *const c_char) -> *mut (),
    pub get_string_utf_length: unsafe extern "C" fn(*mut JniEnv, *mut ()) -> c_int,
    pub get_string_utf_chars: unsafe extern "C" fn(*mut JniEnv, *mut (), *mut u8) -> *const c_char,
    pub release_string_utf_chars: unsafe extern "C" fn(*mut JniEnv, *mut (), *const c_char),
}

struct WorldStore {
    blocks: HashMap<(i32, i32, i32), i32>,
    code_blocks: HashMap<(i32, i32, i32), String>,
    world_code: String,
}

static STORE: OnceLock<Mutex<WorldStore>> = OnceLock::new();

fn store() -> &'static Mutex<WorldStore> {
    STORE.get_or_init(|| {
        Mutex::new(WorldStore {
            blocks: HashMap::with_capacity(4096),
            code_blocks: HashMap::with_capacity(64),
            world_code: String::new(),
        })
    })
}

unsafe fn jstring_to_rust(env: *mut JniEnv, jstr: *mut ()) -> String {
    if env.is_null() || jstr.is_null() {
        return String::new();
    }
    let funcs = (*env).functions;
    if funcs.is_null() {
        return String::new();
    }
    let ptr = ((*funcs).get_string_utf_chars)(env, jstr, std::ptr::null_mut());
    if ptr.is_null() {
        return String::new();
    }
    let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
    ((*funcs).release_string_utf_chars)(env, jstr, ptr);
    s
}

unsafe fn rust_to_jstring(env: *mut JniEnv, s: &str) -> *mut () {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let funcs = (*env).functions;
    if funcs.is_null() {
        return std::ptr::null_mut();
    }
    let clean = s.replace('\0', "");
    let cstr = CString::new(clean).unwrap_or_else(|_| CString::new("{}").unwrap());
    ((*funcs).new_string_utf)(env, cstr.as_ptr())
}

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {}
            c => out.push(c),
        }
    }
    out
}

#[no_mangle]
pub extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustVersion(
    _env: *mut JniEnv,
    _cls: *mut (),
) -> c_int {
    51
}

#[no_mangle]
pub extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustPutBlock(
    _env: *mut JniEnv,
    _cls: *mut (),
    x: c_int,
    y: c_int,
    z: c_int,
    block_id: c_int,
) -> c_int {
    if let Ok(mut st) = store().lock() {
        st.blocks.insert((x, y, z), block_id);
        return st.blocks.len() as c_int;
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustGetBlock(
    _env: *mut JniEnv,
    _cls: *mut (),
    x: c_int,
    y: c_int,
    z: c_int,
) -> c_int {
    if let Ok(st) = store().lock() {
        if let Some(&id) = st.blocks.get(&(x, y, z)) {
            return id;
        }
    }
    -1
}

#[no_mangle]
pub extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustClearBlocks(
    _env: *mut JniEnv,
    _cls: *mut (),
) -> c_int {
    if let Ok(mut st) = store().lock() {
        let count = st.blocks.len() as c_int;
        st.blocks.clear();
        return count;
    }
    0
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustSaveCodeBlock(
    env: *mut JniEnv,
    _cls: *mut (),
    x: c_int,
    y: c_int,
    z: c_int,
    jcode: *mut (),
) -> c_int {
    let code = jstring_to_rust(env, jcode);
    if let Ok(mut st) = store().lock() {
        if x == i32::MIN && y == i32::MIN && z == i32::MIN {
            st.world_code = code;
        } else {
            st.code_blocks.insert((x, y, z), code);
        }
        return 1;
    }
    0
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustExportWorldJson(
    env: *mut JniEnv,
    _cls: *mut (),
) -> *mut () {
    let json = if let Ok(st) = store().lock() {
        let mut b_parts = Vec::with_capacity(st.blocks.len());
        for (&(x, y, z), &id) in &st.blocks {
            b_parts.push(format!("[{},{},{},{}]", x, y, z, id));
        }
        let mut c_parts = Vec::with_capacity(st.code_blocks.len());
        for (&(x, y, z), code) in &st.code_blocks {
            c_parts.push(format!(
                "{{\"x\":{},\"y\":{},\"z\":{},\"code\":\"{}\"}}",
                x,
                y,
                z,
                escape_json(code)
            ));
        }
        format!(
            "{{\"blocks\":[{}],\"codeBlocks\":[{}],\"worldCode\":\"{}\"}}",
            b_parts.join(","),
            c_parts.join(","),
            escape_json(&st.world_code)
        )
    } else {
        "{\"blocks\":[],\"codeBlocks\":[],\"worldCode\":\"\"}".to_string()
    };
    rust_to_jstring(env, &json)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustLoadWorldEdits(
    env: *mut JniEnv,
    _cls: *mut (),
    jflat: *mut (),
) -> c_int {
    let raw = jstring_to_rust(env, jflat);
    let mut loaded = 0;
    if let Ok(mut st) = store().lock() {
        st.blocks.clear();
        for rec in raw.split(';') {
            if rec.is_empty() {
                continue;
            }
            let parts: Vec<&str> = rec.split(',').collect();
            if parts.len() == 4 {
                if let (Ok(x), Ok(y), Ok(z), Ok(id)) = (
                    parts[0].parse::<i32>(),
                    parts[1].parse::<i32>(),
                    parts[2].parse::<i32>(),
                    parts[3].parse::<i32>(),
                ) {
                    st.blocks.insert((x, y, z), id);
                    loaded += 1;
                }
            }
        }
    }
    loaded
}

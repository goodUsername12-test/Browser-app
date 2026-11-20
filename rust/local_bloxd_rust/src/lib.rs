use std::collections::HashMap;
use std::os::raw::c_int;
use std::sync::{Mutex, OnceLock};

const MAX_BLOCKS: usize = 250_000;
const MAX_CODE_BLOCKS: usize = 4_096;
const MAX_CODE_BYTES: usize = 2 * 1024 * 1024;

#[repr(C)]
pub struct JniEnv {
    pub functions: *const JniNativeInterface,
}

/// Verified standard JNINativeInterface_ vtable layout (OpenJDK / Android NDK jni.h):
///   0..16   : reserved0..3 .. ExceptionDescribe (17 slots)
///   17      : ExceptionClear
///   18..162 : FatalError .. SetStaticDoubleField (145 slots)
///   163     : NewString (UTF-16)
///   164     : GetStringLength (UTF-16 code units)
///   165     : GetStringChars (UTF-16)
///   166     : ReleaseStringChars (UTF-16)
///   167..227: NewStringUTF .. MonitorExit (61 slots)
///   228     : ExceptionCheck
#[repr(C)]
pub struct JniNativeInterface {
    _reserved0: [*const (); 17],
    pub exception_clear: unsafe extern "system" fn(*mut JniEnv),
    _reserved1: [*const (); 145],
    pub new_string: unsafe extern "system" fn(*mut JniEnv, *const u16, c_int) -> *mut (),
    pub get_string_length: unsafe extern "system" fn(*mut JniEnv, *mut ()) -> c_int,
    pub get_string_chars: unsafe extern "system" fn(*mut JniEnv, *mut (), *mut u8) -> *const u16,
    pub release_string_chars: unsafe extern "system" fn(*mut JniEnv, *mut (), *const u16),
    _reserved2: [*const (); 61],
    pub exception_check: unsafe extern "system" fn(*mut JniEnv) -> u8,
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

fn clamp_utf8_len(mut s: String, max_bytes: usize) -> String {
    if s.len() <= max_bytes {
        return s;
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s.truncate(end);
    s
}

unsafe fn jstring_to_rust(env: *mut JniEnv, jstr: *mut ()) -> String {
    if env.is_null() || jstr.is_null() {
        return String::new();
    }
    let funcs = (*env).functions;
    if funcs.is_null() {
        return String::new();
    }
    let len = ((*funcs).get_string_length)(env, jstr);
    if len <= 0 {
        return String::new();
    }
    let capped_units = (len as usize).min(MAX_CODE_BYTES);
    let ptr = ((*funcs).get_string_chars)(env, jstr, std::ptr::null_mut());
    if ptr.is_null() {
        if ((*funcs).exception_check)(env) != 0 {
            ((*funcs).exception_clear)(env);
        }
        return String::new();
    }
    let slice = std::slice::from_raw_parts(ptr, capped_units);
    let s = String::from_utf16_lossy(slice);
    ((*funcs).release_string_chars)(env, jstr, ptr);
    clamp_utf8_len(s, MAX_CODE_BYTES)
}

unsafe fn rust_to_jstring(env: *mut JniEnv, s: &str) -> *mut () {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let funcs = (*env).functions;
    if funcs.is_null() {
        return std::ptr::null_mut();
    }
    let utf16: Vec<u16> = s.encode_utf16().collect();
    let len = utf16.len().min(i32::MAX as usize) as c_int;
    let ptr = if utf16.is_empty() {
        std::ptr::null()
    } else {
        utf16.as_ptr()
    };
    let out = ((*funcs).new_string)(env, ptr, len);
    if out.is_null() && ((*funcs).exception_check)(env) != 0 {
        ((*funcs).exception_clear)(env);
    }
    out
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
pub extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustVersion(
    _env: *mut JniEnv,
    _cls: *mut (),
) -> c_int {
    52
}

#[no_mangle]
pub extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustPutBlock(
    _env: *mut JniEnv,
    _cls: *mut (),
    x: c_int,
    y: c_int,
    z: c_int,
    block_id: c_int,
) -> c_int {
    if let Ok(mut st) = store().lock() {
        if st.blocks.len() < MAX_BLOCKS || st.blocks.contains_key(&(x, y, z)) {
            st.blocks.insert((x, y, z), block_id);
        }
        return st.blocks.len().min(i32::MAX as usize) as c_int;
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustGetBlock(
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
pub extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustClearBlocks(
    _env: *mut JniEnv,
    _cls: *mut (),
) -> c_int {
    if let Ok(mut st) = store().lock() {
        let count = st.blocks.len().min(i32::MAX as usize) as c_int;
        st.blocks.clear();
        return count;
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustSaveCodeBlock(
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
        } else if st.code_blocks.len() < MAX_CODE_BLOCKS || st.code_blocks.contains_key(&(x, y, z)) {
            st.code_blocks.insert((x, y, z), code);
        }
        return 1;
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustExportWorldJson(
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
pub unsafe extern "system" fn Java_com_fable_localbloxd_NativeBloxdBridge_nativeRustLoadWorldEdits(
    env: *mut JniEnv,
    _cls: *mut (),
    jflat: *mut (),
) -> c_int {
    let raw = jstring_to_rust(env, jflat);
    let mut loaded: usize = 0;
    if let Ok(mut st) = store().lock() {
        st.blocks.clear();
        for rec in raw.split(';') {
            if loaded >= MAX_BLOCKS {
                break;
            }
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
    loaded.min(i32::MAX as usize) as c_int
}

//! A C entry point over the hfcast port of VOACAP, for the Prop tab (issue #57).
//!
//! One function in, one out: a request object goes in as JSON and the prediction comes back as
//! JSON, both in the shape `hfcast::service::run` defines (the same function hfcast's own
//! `predict` program calls, so there is no second reading of the request to drift from it).
//!
//! Two things are enforced here rather than trusted to the caller:
//! - The data root is always the compiled-in one. A request naming its own `itshfbc` path is
//!   refused, so nothing passed in can make the engine open files.
//! - No panic crosses the C boundary: each call is wrapped, and a panic becomes an error.

use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use hfcast::json::{self, Json};

/// A request is a few hundred bytes; anything past this is refused unread.
pub const MAX_REQUEST_BYTES: usize = 64 * 1024;

/// What the engine reads its data from: only what is compiled in.
const EMBEDDED_ROOT: &str = "<embedded>";

/// Runs one prediction: JSON request in, JSON prediction (or an error message) out.
pub fn run(request: &str) -> Result<String, String> {
    if request.len() > MAX_REQUEST_BYTES {
        return Err(format!("request is longer than {MAX_REQUEST_BYTES} bytes"));
    }
    let mut req = json::parse(request)?;
    let Json::Obj(fields) = &mut req else {
        return Err("request must be a JSON object".to_string());
    };
    match fields.get("itshfbc") {
        None => {}
        Some(Json::Str(s)) if s == EMBEDDED_ROOT => {}
        Some(_) => return Err("the data root is fixed; leave \"itshfbc\" out".to_string()),
    }
    fields.insert("itshfbc".to_string(), Json::Str(EMBEDDED_ROOT.to_string()));
    let text = req.write();
    catch_unwind(AssertUnwindSafe(|| hfcast::service::run(&text)))
        .unwrap_or_else(|_| Err("the prediction engine stopped on an internal error".to_string()))
}

/// Runs [`run`] for C. Returns a new NUL-terminated string to release with
/// [`js8voacap_free`]: the prediction when `*ok` is set to 1, an error message when it is 0.
/// Returns null only if `request` is null or the answer can't be returned.
///
/// # Safety
/// `request` must be null or point to a NUL-terminated string valid for the call, and `ok` must be
/// null or point to a writable `i32`.
#[no_mangle]
pub unsafe extern "C" fn js8voacap_run(request: *const c_char, ok: *mut i32) -> *mut c_char {
    let answer = catch_unwind(AssertUnwindSafe(|| {
        if request.is_null() {
            return None;
        }
        // SAFETY: non-null, and the caller promises a NUL-terminated string.
        let bytes = unsafe { CStr::from_ptr(request) }.to_bytes();
        let result = match std::str::from_utf8(bytes) {
            Ok(text) => run(text),
            Err(_) => Err("request is not UTF-8".to_string()),
        };
        Some(result)
    }))
    .unwrap_or_else(|_| Some(Err("the prediction engine stopped on an internal error".to_string())));
    let Some(result) = answer else { return std::ptr::null_mut() };
    let (flag, text) = match result {
        Ok(t) => (1, t),
        Err(e) => (0, e),
    };
    if !ok.is_null() {
        // SAFETY: non-null, and the caller promises it is writable.
        unsafe { *ok = flag };
    }
    // JSON from the engine holds no NUL; an error message might in theory, so strip any.
    CString::new(text.replace('\0', " ")).map_or(std::ptr::null_mut(), CString::into_raw)
}

/// Releases a string returned by [`js8voacap_run`]. Null is ignored.
///
/// # Safety
/// `s` must be null or a pointer returned by [`js8voacap_run`] that has not been freed.
#[no_mangle]
pub unsafe extern "C" fn js8voacap_free(s: *mut c_char) {
    if !s.is_null() {
        // SAFETY: the caller promises this came from CString::into_raw in js8voacap_run.
        drop(unsafe { CString::from_raw(s) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(extra: &str) -> String {
        format!(
            "{{\"fromLat\":35.8,\"fromLon\":-78.6,\"toLat\":41.9,\"toLon\":-87.6,\"month\":11,\
             \"year\":2026,\"ssn\":90,\"watts\":20,\"bands\":[3.578,7.078,14.078,28.078],\
             \"requiredSnrDb\":10,\"noiseDbw\":145{extra}}}"
        )
    }

    #[test]
    fn predicts_a_day() {
        let out = run(&request("")).expect("prediction");
        let v = json::parse(&out).unwrap();
        assert_eq!(v.get("engine").and_then(Json::as_str), Some("voacap"));
        let Some(Json::Arr(muf)) = v.get("mufByHour") else { panic!("no mufByHour: {out}") };
        assert_eq!(muf.len(), 24);
        let Some(Json::Arr(cells)) = v.get("cells") else { panic!("no cells") };
        assert!(!cells.is_empty());
    }

    #[test]
    fn refuses_a_data_path() {
        let err = run(&request(",\"itshfbc\":\"/etc\"")).unwrap_err();
        assert!(err.contains("data root"), "{err}");
        assert!(run(&request(",\"itshfbc\":\"<embedded>\"")).is_ok());
    }

    #[test]
    fn refuses_bad_input_without_panicking() {
        assert!(run("").is_err());
        assert!(run("[1,2]").is_err());
        assert!(run("{\"fromLat\":\"x\"}").is_err());
        assert!(run(&"x".repeat(MAX_REQUEST_BYTES + 1)).is_err());
    }

    #[test]
    fn c_entry_point_round_trip() {
        let req = CString::new(request("")).unwrap();
        let mut ok = -1;
        let p = unsafe { js8voacap_run(req.as_ptr(), &mut ok) };
        assert!(!p.is_null());
        assert_eq!(ok, 1);
        let text = unsafe { CStr::from_ptr(p) }.to_str().unwrap().to_owned();
        unsafe { js8voacap_free(p) };
        assert!(text.contains("\"cells\""));
        assert!(unsafe { js8voacap_run(std::ptr::null(), &mut ok) }.is_null());
        unsafe { js8voacap_free(std::ptr::null_mut()) };
    }
}

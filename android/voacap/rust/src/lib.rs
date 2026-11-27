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
#[cfg(not(fuzzing))]
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
    check_ranges(fields)?;
    if fields.get("mode").and_then(Json::as_str) == Some("area") {
        check_area_size(fields)?;
    }
    let text = req.write();
    guarded(|| hfcast::service::run(&text))
}

/// Positions and the month are checked here as well as by the app: fuzzing found that an area run
/// given a longitude far outside -180..180 indexed past a table in the engine (a panic, caught in
/// release builds, but no answer either way).
fn check_ranges(fields: &std::collections::BTreeMap<String, Json>) -> Result<(), String> {
    let limits: [(&str, f64, f64); 5] = [
        ("fromLat", -90.0, 90.0),
        ("toLat", -90.0, 90.0),
        ("fromLon", -180.0, 180.0),
        ("toLon", -180.0, 180.0),
        ("month", 1.0, 12.0),
    ];
    for (key, lo, hi) in limits {
        match fields.get(key) {
            None => {}
            Some(Json::Num(n)) if n.is_finite() && *n >= lo && *n <= hi => {}
            Some(_) => return Err(format!("\"{key}\" must be a number from {lo} to {hi}")),
        }
    }
    if let Some(Json::Num(m)) = fields.get("month") {
        if m.fract() != 0.0 {
            return Err("\"month\" must be a whole number".to_string());
        }
    }
    Ok(())
}

/// The most grid points one area run may cover: the Prop tab's runs are a few thousand, and a
/// request for more is refused before any work, so a typo (or a fuzzer) can't tie the engine up
/// for minutes or exhaust memory.
pub const MAX_AREA_POINTS: f64 = 250_000.0;

fn check_area_size(fields: &std::collections::BTreeMap<String, Json>) -> Result<(), String> {
    let num = |k: &str| match fields.get(k) {
        Some(Json::Num(n)) => Some(*n),
        _ => None,
    };
    let (Some(dlat), Some(dlon)) = (num("latStep"), num("lonStep")) else {
        return Ok(()); // the engine reports the missing field itself
    };
    if !(dlat.is_finite() && dlon.is_finite() && dlat > 0.0 && dlon > 0.0) {
        return Err("\"latStep\" and \"lonStep\" must be positive".to_string());
    }
    let (lat_span, lon_span) = match (num("latMin"), num("latMax"), num("lonMin"), num("lonMax")) {
        (Some(a), Some(b), Some(c), Some(d)) => ((b - a).abs().min(180.0), (d - c).abs().min(360.0)),
        _ => (180.0, 360.0),
    };
    let points = (lat_span / dlat + 1.0) * (lon_span / dlon + 1.0);
    if !points.is_finite() || points > MAX_AREA_POINTS {
        return Err(format!("an area run may cover at most {MAX_AREA_POINTS} points"));
    }
    Ok(())
}

/// Runs the engine, turning a panic into an error. Fuzzing builds (`--cfg fuzzing`) let the panic
/// through, so the fuzzer sees it as the crash it is.
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    #[cfg(fuzzing)]
    {
        f()
    }
    #[cfg(not(fuzzing))]
    {
        catch_unwind(AssertUnwindSafe(f))
            .unwrap_or_else(|_| Err("the prediction engine stopped on an internal error".to_string()))
    }
}

/// An area run (VOACAP's coverage map), packed for the phone: a 28-byte header of seven
/// little-endian f32 (latitude of the first row, row step, rows, longitude of the first column,
/// column step, columns, bands), then for each row south to north, each column west to east,
/// each band in request order: reliability as one byte, 0 to 255. Cells the engine left out
/// stay 0.
pub fn area(request: &str) -> Result<Vec<u8>, String> {
    let req = json::parse(request)?;
    if req.get("mode").and_then(Json::as_str) != Some("area") {
        return Err("an area request says \"mode\": \"area\"".to_string());
    }
    let out = run(request)?;
    let v = json::parse(&out)?;
    let num = |k: &str| match v.get(k) {
        Some(Json::Num(n)) => Ok(*n),
        _ => Err(format!("area answer has no {k}")),
    };
    let (lat0, lat1, dlat) = (num("latMin")?, num("latMax")?, num("latStep")?);
    let (lon0, lon1, dlon) = (num("lonMin")?, num("lonMax")?, num("lonStep")?);
    if !(dlat > 0.0 && dlon > 0.0) {
        return Err("area answer has no grid step".to_string());
    }
    let rows = ((lat1 - lat0) / dlat).round() as usize + 1;
    let cols = ((lon1 - lon0) / dlon).round() as usize + 1;
    let bands = match v.get("freqsMhz") {
        Some(Json::Arr(a)) => a.len(),
        _ => 1,
    };
    let cells = rows.checked_mul(cols).and_then(|n| n.checked_mul(bands)).filter(|&n| n <= 4_000_000)
        .ok_or("area grid too large")?;
    let mut buf = Vec::with_capacity(28 + cells);
    for x in [lat0, dlat, rows as f64, lon0, dlon, cols as f64, bands as f64] {
        buf.extend_from_slice(&(x as f32).to_le_bytes());
    }
    buf.resize(28 + cells, 0);
    let Some(Json::Arr(points)) = v.get("points") else { return Err("area answer has no points".to_string()) };
    for p in points {
        let (Some(Json::Num(lat)), Some(Json::Num(lon))) = (p.get("lat"), p.get("lon")) else { continue };
        let r = ((lat - lat0) / dlat).round();
        let c = ((lon - lon0) / dlon).round();
        if r < 0.0 || c < 0.0 || r as usize >= rows || c as usize >= cols {
            continue;
        }
        let rel: Vec<f64> = match p.get("reliability") {
            Some(Json::Arr(a)) => a.iter().map(|x| x.as_f64().unwrap_or(0.0)).collect(),
            Some(Json::Num(n)) => vec![*n],
            _ => continue,
        };
        let at = 28 + (r as usize * cols + c as usize) * bands;
        for (i, x) in rel.iter().take(bands).enumerate() {
            buf[at + i] = (x.clamp(0.0, 1.0) * 255.0).round() as u8;
        }
    }
    Ok(buf)
}

/// Runs [`area`] for C. On success returns the packed grid and sets `*len`; on failure returns
/// null and sets `*err` to a message (release it with [`js8voacap_free`]). Release the grid with
/// [`js8voacap_free_bytes`].
///
/// # Safety
/// `request` must be null or a NUL-terminated string; `len` and `err` must be valid for writes.
#[no_mangle]
pub unsafe extern "C" fn js8voacap_area(request: *const c_char, len: *mut usize, err: *mut *mut c_char) -> *mut u8 {
    let result = guarded(|| {
        if request.is_null() {
            return Err("no request".to_string());
        }
        // SAFETY: non-null, and the caller promises a NUL-terminated string.
        let bytes = unsafe { CStr::from_ptr(request) }.to_bytes();
        let text = std::str::from_utf8(bytes).map_err(|_| "request is not UTF-8".to_string())?;
        area(text)
    });
    match result {
        Ok(buf) => {
            let boxed = buf.into_boxed_slice();
            // SAFETY: the caller promises len is writable.
            unsafe { *len = boxed.len() };
            Box::into_raw(boxed) as *mut u8
        }
        Err(e) => {
            // SAFETY: the caller promises err is writable.
            unsafe { *err = CString::new(e.replace('\0', " ")).map_or(std::ptr::null_mut(), CString::into_raw) };
            std::ptr::null_mut()
        }
    }
}

/// Releases a grid returned by [`js8voacap_area`]. Null is ignored.
///
/// # Safety
/// `p` and `len` must be exactly what [`js8voacap_area`] returned, not yet freed.
#[no_mangle]
pub unsafe extern "C" fn js8voacap_free_bytes(p: *mut u8, len: usize) {
    if !p.is_null() {
        // SAFETY: rebuilt from Box::into_raw of a boxed slice of this length.
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, len)) });
    }
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
    if request.is_null() {
        return std::ptr::null_mut();
    }
    let result = guarded(|| {
        // SAFETY: non-null, and the caller promises a NUL-terminated string.
        let bytes = unsafe { CStr::from_ptr(request) }.to_bytes();
        match std::str::from_utf8(bytes) {
            Ok(text) => run(text),
            Err(_) => Err("request is not UTF-8".to_string()),
        }
    });
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
    fn area_packs_a_world_grid() {
        let req = "{\"mode\":\"area\",\"fromLat\":35.8,\"fromLon\":-78.6,\"month\":11,\"year\":2026,\
                   \"ssn\":90,\"watts\":20,\"freqsMhz\":[7.078,14.078],\"requiredSnrDb\":10,\
                   \"noiseDbw\":145,\"hour\":15,\"latStep\":10,\"lonStep\":10}";
        let buf = area(req).expect("area");
        let f = |i: usize| f32::from_le_bytes(buf[i * 4..i * 4 + 4].try_into().unwrap());
        let (rows, cols, bands) = (f(2) as usize, f(5) as usize, f(6) as usize);
        assert_eq!((rows, cols, bands), (18, 36, 2));
        assert_eq!(buf.len(), 28 + rows * cols * bands);
        assert!((f(0) + 85.0).abs() < 1e-3 && (f(3) + 175.0).abs() < 1e-3, "{} {}", f(0), f(3));
        // Some of the world is reachable on 20 m from North Carolina at 15 UTC in November.
        assert!(buf[28..].chunks(2).any(|c| c[1] > 128));
        assert!(area("{\"fromLat\":1}").is_err());
    }

    #[test]
    fn refuses_an_area_too_big_to_run() {
        let req = "{\"mode\":\"area\",\"fromLat\":35.8,\"fromLon\":-78.6,\"month\":11,\"year\":2026,\
                   \"ssn\":90,\"watts\":20,\"freqsMhz\":[7.078],\"requiredSnrDb\":10,\
                   \"noiseDbw\":145,\"hour\":15,\"latStep\":0.1,\"lonStep\":0.1}";
        let err = area(req).unwrap_err();
        assert!(err.contains("at most"), "{err}");
        // A small bounded window at the same step is fine.
        let small = req.replace("\"hour\":15,", "\"hour\":15,\"latMin\":30,\"latMax\":31,\"lonMin\":-80,\"lonMax\":-79,");
        assert!(area(&small).is_ok());
        assert!(run(&req.replace("0.1", "-1")).is_err());
    }

    #[test]
    fn refuses_positions_off_the_map() {
        // Found by fuzzing: an area run with fromLon -7826 indexed past a table in the engine.
        let req = "{\"mode\":\"area\",\"fromLat\":32.8,\"fromLon\":-7826,\"month\":11,\"year\":2026,\
                   \"ssn\":90,\"watts\":20,\"freqsMhz\":[5.078,14.068],\"requiredSnrDb\":4,\
                   \"noiseDbw\":145,\"hour\":15,\"latStep\":16,\"lonStep\":15}";
        assert!(area(req).unwrap_err().contains("fromLon"));
        assert!(run(&request("").replace("\"month\":11", "\"month\":13")).is_err());
        assert!(run(&request("").replace("\"toLat\":41.9", "\"toLat\":91")).is_err());
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

// Batch VOACAP for the model comparison. stdin lines:
//   id \t txlat txlon rxlat rxlon year month ssn noise \t h:b,h:b,...
// (b indexes BANDS). stdout lines: id \t h \t b \t snr \t dl \t du \t rel  (1 W, isotropic)
use hfcast::json::{self, Json};
use std::io::{BufRead, Write};
use std::sync::{mpsc, Arc, Mutex};

const BANDS: [f64; 10] = [1.8366, 3.5686, 5.2872, 7.0386, 10.1387, 14.0956, 18.1046, 21.0946, 24.9246, 28.1246];

fn num(v: Option<&Json>) -> f64 {
    match v { Some(Json::Num(n)) => *n, _ => f64::NAN }
}

fn one(line: &str) -> Vec<String> {
    let mut parts = line.split('\t');
    let id = parts.next().unwrap();
    let p: Vec<f64> = parts.next().unwrap().split(' ').map(|x| x.parse().unwrap()).collect();
    let want: Vec<(i64, usize)> = parts.next().unwrap().split(',').map(|s| {
        let mut it = s.split(':');
        (it.next().unwrap().parse().unwrap(), it.next().unwrap().parse().unwrap())
    }).collect();
    let bands: Vec<String> = BANDS.iter().map(|b| format!("{b}")).collect();
    let req = format!(
        "{{\"itshfbc\":\"<embedded>\",\"fromLat\":{},\"fromLon\":{},\"toLat\":{},\"toLon\":{},\"year\":{},\"month\":{},\"ssn\":{},\"watts\":1,\"bands\":[{}],\"requiredSnrDb\":6,\"noiseDbw\":{}}}",
        p[0], p[1], p[2], p[3], p[4] as i64, p[5] as i64, p[6], bands.join(","), p[7]);
    let out = match hfcast::service::run(&req) {
        Ok(o) => o,
        Err(e) => { eprintln!("{id}: {e}"); return vec![]; }
    };
    let v = json::parse(&out).unwrap();
    let Some(Json::Arr(cells)) = v.get("cells") else { return vec![] };
    let mut res = Vec::new();
    for c in cells {
        let h = num(c.get("hour")) as i64;
        let f = num(c.get("freqMhz"));
        for &(wh, wb) in &want {
            if wh == h && (BANDS[wb] - f).abs() < 0.01 {
                res.push(format!("{id}\t{h}\t{wb}\t{}\t{}\t{}\t{}", num(c.get("snr")), num(c.get("snrLowDecile")),
                    num(c.get("snrUpDecile")), num(c.get("reliability"))));
            }
        }
    }
    res
}

fn main() {
    let lines: Vec<String> = std::io::stdin().lock().lines().map(|l| l.unwrap()).filter(|l| !l.is_empty()).collect();
    let work = Arc::new(Mutex::new(lines.into_iter()));
    let (tx, rx) = mpsc::channel::<Vec<String>>();
    let n = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2);
    for _ in 0..n {
        let work = work.clone(); let tx = tx.clone();
        std::thread::spawn(move || loop {
            let next = work.lock().unwrap().next();
            match next { Some(l) => { tx.send(one(&l)).unwrap(); } None => break }
        });
    }
    drop(tx);
    let mut out = std::io::BufWriter::new(std::io::stdout().lock());
    for batch in rx { for l in batch { writeln!(out, "{l}").unwrap(); } }
}

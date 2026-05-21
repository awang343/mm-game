use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Response, Server};

#[derive(Serialize, Deserialize, Clone)]
struct Contract {
    id: u32,
    question: String,
    // `answer` is int or float in the JSON; keep raw so we round-trip exactly.
    answer: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct Config {
    #[serde(default = "default_data_path")]
    data_path: PathBuf,
    #[serde(default = "default_host")]
    host: String,
    #[serde(default = "default_port")]
    port: u16,
}

fn default_data_path() -> PathBuf {
    PathBuf::from("./data/contracts.json")
}
fn default_host() -> String {
    "0.0.0.0".to_string()
}
fn default_port() -> u16 {
    7878
}

fn config_path() -> PathBuf {
    dirs::config_dir()
        .map(|p| p.join("mm-server").join("config.toml"))
        .unwrap_or_else(|| PathBuf::from("config.toml"))
}

fn load_or_create_config() -> Config {
    let path = config_path();
    if path.exists() {
        let raw = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("read config {}: {}", path.display(), e));
        toml::from_str(&raw)
            .unwrap_or_else(|e| panic!("parse config {}: {}", path.display(), e))
    } else {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .unwrap_or_else(|e| panic!("create config dir {}: {}", parent.display(), e));
        }
        let body = format!(
            "# Market Making — server config\n\
             # Path to the contracts.json the server should serve.\n\
             # Run `populate.py` in the server/ dir to generate this file.\n\n\
             data_path = \"{}\"\n\
             host = \"{}\"\n\
             port = {}\n",
            default_data_path().display(),
            default_host(),
            default_port(),
        );
        fs::write(&path, body)
            .unwrap_or_else(|e| panic!("write default config {}: {}", path.display(), e));
        eprintln!("[server] wrote default config to {}", path.display());
        Config {
            data_path: default_data_path(),
            host: default_host(),
            port: default_port(),
        }
    }
}

fn pseudo_random_index(len: usize) -> usize {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as usize)
        .unwrap_or(0);
    nanos % len.max(1)
}

fn json_response<T: Serialize>(body: &T) -> Response<std::io::Cursor<Vec<u8>>> {
    let payload = serde_json::to_vec(body).expect("serialize");
    Response::from_data(payload)
        .with_header("Content-Type: application/json".parse::<Header>().unwrap())
        .with_header("Access-Control-Allow-Origin: *".parse::<Header>().unwrap())
}

fn not_found() -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(r#"{"error":"not found"}"#)
        .with_status_code(404)
        .with_header("Content-Type: application/json".parse::<Header>().unwrap())
}

fn main() {
    let cfg = load_or_create_config();

    let contracts: Vec<Contract> = {
        let raw = fs::read_to_string(&cfg.data_path).unwrap_or_else(|e| {
            panic!(
                "read contracts file {}: {}\n\
                 Edit data_path in {} to point at your contracts.json.",
                cfg.data_path.display(),
                e,
                config_path().display(),
            )
        });
        serde_json::from_str(&raw)
            .unwrap_or_else(|e| panic!("parse {}: {}", cfg.data_path.display(), e))
    };

    let addr = format!("{}:{}", cfg.host, cfg.port);
    let server = Server::http(&addr).unwrap_or_else(|e| panic!("bind {}: {}", addr, e));
    eprintln!(
        "[server] listening on http://{} — {} contracts (data: {})",
        addr,
        contracts.len(),
        cfg.data_path.display(),
    );

    for request in server.incoming_requests() {
        let path = request.url().split('?').next().unwrap_or("").to_string();
        let response = match path.as_str() {
            "/" | "/health" => json_response(&serde_json::json!({
                "ok": true,
                "contracts": contracts.len(),
            })),
            "/contracts" => json_response(&contracts),
            "/contracts/random" => {
                let i = pseudo_random_index(contracts.len());
                json_response(&contracts[i])
            }
            _ => not_found(),
        };
        let _ = request.respond(response);
    }
}

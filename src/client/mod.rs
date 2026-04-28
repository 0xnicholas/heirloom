use reqwest::header::{HeaderMap, HeaderValue};
use reqwest::Client;
use std::collections::HashMap;
use std::time::Duration;

pub struct HttpClient {
    client: Client,
}

impl HttpClient {
    pub fn new(
        timeout_seconds: u64,
        extra_headers: &HashMap<String, String>,
        proxy_url: Option<&str>,
        enforce_http2: bool,
    ) -> anyhow::Result<Self> {
        let mut builder = Client::builder()
            .timeout(Duration::from_secs(timeout_seconds))
            .connect_timeout(Duration::from_secs(10))
            .pool_max_idle_per_host(100)
            .pool_idle_timeout(Duration::from_secs(90))
            .tcp_keepalive(Duration::from_secs(60));

        // Apply default headers from network config
        if !extra_headers.is_empty() {
            let mut headers = HeaderMap::new();
            for (key, value) in extra_headers {
                if let (Ok(name), Ok(val)) = (
                    key.parse::<reqwest::header::HeaderName>(),
                    HeaderValue::from_str(value),
                ) {
                    headers.insert(name, val);
                }
            }
            builder = builder.default_headers(headers);
        }

        // Apply proxy
        if let Some(proxy_url) = proxy_url {
            let proxy = reqwest::Proxy::all(proxy_url)
                .map_err(|e| anyhow::anyhow!("Invalid proxy URL: {}", e))?;
            builder = builder.proxy(proxy);
        }

        // Enforce HTTP/2
        if enforce_http2 {
            builder = builder.http2_prior_knowledge();
        }

        let client = builder
            .build()
            .map_err(|e| anyhow::anyhow!("Failed to build HTTP client: {}", e))?;

        Ok(Self { client })
    }

    pub fn inner(&self) -> &Client {
        &self.client
    }
}

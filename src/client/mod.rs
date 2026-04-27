use reqwest::Client;
use std::time::Duration;

pub struct HttpClient {
    client: Client,
}

impl HttpClient {
    pub fn new(timeout_seconds: u64) -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(timeout_seconds))
            .pool_max_idle_per_host(100)
            .build()
            .expect("Failed to build HTTP client");
        
        Self { client }
    }
    
    pub fn inner(&self) -> &Client {
        &self.client
    }
}

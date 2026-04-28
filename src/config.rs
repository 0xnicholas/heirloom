use serde::Deserialize;
use std::collections::HashMap;
use std::path::Path;
use thiserror::Error;
use url::Url;

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub providers: HashMap<String, ProviderConfig>,
    #[serde(default)]
    pub fallbacks: Vec<FallbackChainConfig>,
    #[serde(default)]
    pub mcp: Option<McpConfig>,
    #[serde(default)]
    pub models: HashMap<String, String>, // model_name -> provider_name
}

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    #[serde(default = "default_host")]
    pub host: String,
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_log_level")]
    pub log_level: String,
    #[serde(default)]
    pub allowed_origins: Vec<String>,
    #[serde(default = "default_max_body_size")]
    pub max_body_size: usize,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ProviderConfig {
    pub enabled: bool,
    #[serde(default = "default_base_url")]
    pub base_url: String,
    pub keys: Vec<ApiKey>,
    #[serde(default = "default_max_retries")]
    pub max_retries: u32,
    #[serde(default = "default_retry_backoff_initial_ms")]
    pub retry_backoff_initial_ms: u64,
    #[serde(default = "default_retry_backoff_max_ms")]
    pub retry_backoff_max_ms: u64,
    #[serde(default = "default_request_timeout_seconds")]
    pub request_timeout_seconds: u64,
    #[serde(default = "default_queue_concurrency")]
    pub queue_concurrency: usize,
    #[serde(default = "default_queue_buffer_size")]
    pub queue_buffer_size: usize,
    #[serde(default)]
    pub network: NetworkConfig,
    #[serde(default)]
    pub rate_limit: RateLimitConfig,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct NetworkConfig {
    #[serde(default)]
    pub extra_headers: HashMap<String, String>,
    #[serde(default)]
    pub insecure_skip_verify: bool,
    #[serde(default)]
    pub enforce_http2: bool,
    #[serde(default)]
    pub proxy_url: Option<String>,
}

// Secret wrapper to prevent accidental logging of API keys
#[derive(Clone, Deserialize)]
pub struct SecretString(String);

impl SecretString {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }
    
    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for SecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("***REDACTED***")
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ApiKey {
    pub value: SecretString,
    #[serde(default = "default_weight")]
    pub weight: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct FallbackChainConfig {
    pub primary: String,
    pub fallbacks: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct McpConfig {
    pub enabled: bool,
    #[serde(default = "default_max_agent_depth")]
    pub max_agent_depth: usize,
    #[serde(default = "default_tool_execution_timeout_seconds")]
    pub tool_execution_timeout_seconds: u64,
    #[serde(default)]
    pub clients: Vec<McpClientConfig>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct McpClientConfig {
    pub name: String,
    pub transport: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub args: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default = "default_auto_execute")]
    pub auto_execute: bool,
    #[serde(default = "default_stdio_concurrency")]
    pub concurrency: usize,
    #[serde(default)]
    pub timeout_seconds: Option<u64>,
}

#[derive(Error, Debug)]
pub enum ConfigError {
    #[error("No providers configured or all disabled")]
    NoProviders,
    #[error("Provider '{0}' has no API keys configured")]
    NoKeys(String),
    #[error("Provider '{0}' has invalid base URL: {1}")]
    InvalidUrl(String, String),
    #[error("Fallback chain references unknown provider: {0}")]
    UnknownFallbackProvider(String),
    #[error("Invalid MCP config: {0}")]
    InvalidMcpConfig(String),
}

impl AppConfig {
    pub fn from_file(path: &Path) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: AppConfig = toml::from_str(&content)?;
        Ok(config)
    }
    
    pub fn from_file_with_env(path: &Path) -> anyhow::Result<Self> {
        let mut config = Self::from_file(path)?;
        
        if let Ok(port) = std::env::var("HEIRLOOM_PORT") {
            config.server.port = port.parse()?;
        }
        if let Ok(level) = std::env::var("HEIRLOOM_LOG_LEVEL") {
            config.server.log_level = level;
        }
        
        Ok(config)
    }
    
    pub fn validate(&self) -> Result<(), ConfigError> {
        let enabled_providers: Vec<&String> = self.providers.iter()
            .filter(|(_, p)| p.enabled)
            .map(|(n, _)| n)
            .collect();
        
        if enabled_providers.is_empty() {
            return Err(ConfigError::NoProviders);
        }
        
        for (name, provider) in &self.providers {
            if !provider.enabled {
                continue;
            }
            
            if provider.keys.is_empty() {
                return Err(ConfigError::NoKeys(name.clone()));
            }
            
            if !provider.base_url.is_empty() {
                Url::parse(&provider.base_url)
                    .map_err(|_| ConfigError::InvalidUrl(name.clone(), provider.base_url.clone()))?;
            }
        }
        
        for chain in &self.fallbacks {
            if !enabled_providers.contains(&&chain.primary) {
                return Err(ConfigError::UnknownFallbackProvider(chain.primary.clone()));
            }
            for fallback in &chain.fallbacks {
                if !enabled_providers.contains(&&fallback) {
                    return Err(ConfigError::UnknownFallbackProvider(fallback.clone()));
                }
            }
        }
        
        // Validate MCP config
        if let Some(mcp) = &self.mcp {
            for client in &mcp.clients {
                match client.transport.as_str() {
                    "stdio" => {
                        if client.command.is_none() {
                            return Err(ConfigError::InvalidMcpConfig(
                                format!("Client '{}' missing command for stdio transport", client.name)
                            ));
                        }
                    }
                    "sse" => {
                        if client.url.is_none() {
                            return Err(ConfigError::InvalidMcpConfig(
                                format!("Client '{}' missing url for SSE transport", client.name)
                            ));
                        }
                    }
                    _ => return Err(ConfigError::InvalidMcpConfig(
                        format!("Client '{}' has invalid transport: {}", client.name, client.transport)
                    )),
                }
            }
        }
        
        Ok(())
    }
}

fn default_host() -> String { "0.0.0.0".to_string() }
fn default_port() -> u16 { 8080 }
fn default_log_level() -> String { "info".to_string() }
fn default_base_url() -> String { "".to_string() }
fn default_max_retries() -> u32 { 3 }
fn default_retry_backoff_initial_ms() -> u64 { 500 }
fn default_retry_backoff_max_ms() -> u64 { 5000 }
fn default_request_timeout_seconds() -> u64 { 30 }
fn default_queue_concurrency() -> usize { 100 }
fn default_queue_buffer_size() -> usize { 1000 }
fn default_weight() -> f64 { 1.0 }
#[derive(Debug, Clone, Deserialize, Default)]
pub struct RateLimitConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub requests_per_second: Option<u32>,
    #[serde(default)]
    pub burst_size: Option<u32>,
}

fn default_max_body_size() -> usize { 10 * 1024 * 1024 } // 10MB
fn default_max_agent_depth() -> usize { 5 }
fn default_tool_execution_timeout_seconds() -> u64 { 30 }
fn default_auto_execute() -> bool { false }
fn default_stdio_concurrency() -> usize { 1 }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_config() {
        let toml = r#"
[server]
host = "0.0.0.0"
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [
    { value = "sk-xxx", weight = 1.0 }
]
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert_eq!(config.server.port, 8080);
        assert!(config.providers.contains_key("openai"));
    }

    #[test]
    fn test_validate_rejects_empty_keys() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = []
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_validate_rejects_no_providers() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = false
base_url = "https://api.openai.com"
keys = [{ value = "sk-xxx", weight = 1.0 }]
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_load_from_file() {
        let toml = r#"
[server]
port = 9090

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]
"#;
        let tmpfile = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(tmpfile.path(), toml).unwrap();
        
        let config = AppConfig::from_file(tmpfile.path()).unwrap();
        assert_eq!(config.server.port, 9090);
    }

    #[test]
    fn test_env_override() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]
"#;
        let tmpfile = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(tmpfile.path(), toml).unwrap();
        
        std::env::set_var("HEIRLOOM_PORT", "9999");
        let config = AppConfig::from_file_with_env(tmpfile.path()).unwrap();
        assert_eq!(config.server.port, 9999);
        std::env::remove_var("HEIRLOOM_PORT");
    }

    #[test]
    fn test_parse_mcp_config() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]

[mcp]
enabled = true
max_agent_depth = 5

[[mcp.clients]]
name = "filesystem"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
auto_execute = true
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.mcp.is_some());
        let mcp = config.mcp.unwrap();
        assert_eq!(mcp.clients.len(), 1);
        assert_eq!(mcp.clients[0].name, "filesystem");
        assert!(mcp.clients[0].auto_execute);
    }

    #[test]
    fn test_mcp_stdio_requires_command() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]

[mcp]
enabled = true

[[mcp.clients]]
name = "filesystem"
transport = "stdio"
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_mcp_sse_requires_url() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]

[mcp]
enabled = true

[[mcp.clients]]
name = "fetch"
transport = "sse"
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_err());
    }
}

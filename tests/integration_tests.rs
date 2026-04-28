#[cfg(test)]
mod integration_tests {
    use std::collections::HashMap;
    use std::sync::Arc;

    use heirloom::config::SecretString;
    use heirloom::config::{AppConfig, RateLimitConfig};
    use heirloom::gateway::fallback::FallbackChain;
    use heirloom::gateway::key_selector::WeightedKey;
    use heirloom::gateway::queue::{GatewayRequest, GatewayResponse, ProviderQueue};
    use heirloom::gateway::rate_limit::{RateLimitConfig as RLConfig, RateLimiter};
    use heirloom::gateway::retry::RetryPolicy;
    use heirloom::provider::anthropic::AnthropicProvider;
    use heirloom::provider::openai::OpenAIProvider;
    use heirloom::types::*;

    #[tokio::test]
    async fn test_provider_instantiation_with_network_config() {
        let keys = vec![WeightedKey {
            value: SecretString::new("test-key"),
            weight: 1.0,
        }];

        let extra_headers = HashMap::new();

        let openai = OpenAIProvider::new(
            "https://api.openai.com".to_string(),
            keys.clone(),
            30,
            &extra_headers,
            None,
            false,
        );
        assert!(openai.is_ok());

        let anthropic = AnthropicProvider::new(
            "https://api.anthropic.com".to_string(),
            keys.clone(),
            30,
            &extra_headers,
            None,
            false,
        );
        assert!(anthropic.is_ok());
    }

    #[tokio::test]
    async fn test_provider_instantiation_with_proxy() {
        let keys = vec![WeightedKey {
            value: SecretString::new("test-key"),
            weight: 1.0,
        }];

        let extra_headers = HashMap::new();

        let provider = OpenAIProvider::new(
            "https://api.openai.com".to_string(),
            keys,
            30,
            &extra_headers,
            Some("http://proxy.example.com:8080"),
            false,
        );
        assert!(provider.is_ok());
    }

    #[tokio::test]
    async fn test_rate_limiter_integration() {
        let config = RLConfig {
            enabled: true,
            requests_per_second: Some(100),
            burst_size: Some(50),
        };

        let limiter = Arc::new(RateLimiter::new(config));

        // Should allow burst up to burst_size (global limiter)
        for _ in 0..50 {
            assert!(limiter.check_rate_limit(Some("key1")).await);
        }

        // 51st request should fail (global burst exhausted)
        assert!(!limiter.check_rate_limit(Some("key1")).await);
        assert!(!limiter.check_rate_limit(Some("key2")).await);
    }

    #[tokio::test]
    async fn test_retry_policy_creation() {
        let _policy = RetryPolicy::new(
            3,
            std::time::Duration::from_millis(500),
            std::time::Duration::from_millis(5000),
        );
        // RetryPolicy created successfully
    }

    #[tokio::test]
    async fn test_gateway_from_config() {
        let toml = r#"
[server]
host = "0.0.0.0"
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [
    { value = "sk-test", weight = 1.0 }
]

[providers.anthropic]
enabled = true
base_url = "https://api.anthropic.com"
keys = [
    { value = "sk-ant-test", weight = 1.0 }
]

[[fallbacks]]
primary = "openai"
fallbacks = ["anthropic"]

[models]
gpt-4 = "openai"
claude-3 = "anthropic"
"#;

        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_ok());

        let gateway = heirloom::gateway::Gateway::from_config(&config);
        assert!(gateway.is_ok());
    }

    #[test]
    fn test_config_parsing_with_network_and_rate_limit() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]

[providers.openai.network]
extra_headers = { "X-Custom" = "value" }
enforce_http2 = true
proxy_url = "http://proxy:8080"

[providers.openai.rate_limit]
enabled = true
requests_per_second = 100
burst_size = 50
"#;

        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.providers.contains_key("openai"));
        let provider = &config.providers["openai"];
        assert!(provider.network.enforce_http2);
        assert_eq!(
            provider.network.proxy_url,
            Some("http://proxy:8080".to_string())
        );
        assert!(provider.rate_limit.enabled);
        assert_eq!(provider.rate_limit.requests_per_second, Some(100));
    }
}

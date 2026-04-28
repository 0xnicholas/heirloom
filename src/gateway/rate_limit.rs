use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Mutex;
use tokio::time::{Duration, Instant};

/// Token bucket rate limiter
pub struct TokenBucket {
    tokens: f64,
    capacity: f64,
    refill_rate: f64, // tokens per second
    last_refill: Instant,
}

impl TokenBucket {
    pub fn new(capacity: u32, refill_per_second: u32) -> Self {
        Self {
            tokens: capacity as f64,
            capacity: capacity as f64,
            refill_rate: refill_per_second as f64,
            last_refill: Instant::now(),
        }
    }

    pub fn try_acquire(&mut self, tokens: u32) -> bool {
        self.refill();
        if self.tokens >= tokens as f64 {
            self.tokens -= tokens as f64;
            true
        } else {
            false
        }
    }

    fn refill(&mut self) {
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_refill).as_secs_f64();
        self.tokens = (self.tokens + elapsed * self.refill_rate).min(self.capacity);
        self.last_refill = now;
    }
}

/// Rate limiter for a single API key
pub struct KeyRateLimiter {
    bucket: Mutex<TokenBucket>,
}

impl KeyRateLimiter {
    pub fn new(capacity: u32, refill_per_second: u32) -> Self {
        Self {
            bucket: Mutex::new(TokenBucket::new(capacity, refill_per_second)),
        }
    }

    pub async fn try_acquire(&self, tokens: u32) -> bool {
        self.bucket.lock().await.try_acquire(tokens)
    }
}

/// Rate limiting configuration
#[derive(Debug, Clone, Default)]
pub struct RateLimitConfig {
    pub requests_per_second: Option<u32>,
    pub burst_size: Option<u32>,
    pub enabled: bool,
}

/// Rate limiter manager
pub struct RateLimiter {
    key_limiters: Mutex<HashMap<String, Arc<KeyRateLimiter>>>,
    global_limiter: Option<Arc<KeyRateLimiter>>,
    config: RateLimitConfig,
}

impl RateLimiter {
    pub fn new(config: RateLimitConfig) -> Self {
        let global_limiter = if config.enabled {
            config.requests_per_second.map(|rps| {
                Arc::new(KeyRateLimiter::new(
                    config.burst_size.unwrap_or(rps),
                    rps,
                ))
            })
        } else {
            None
        };

        Self {
            key_limiters: Mutex::new(HashMap::new()),
            global_limiter,
            config,
        }
    }

    /// Check if request should be allowed
    pub async fn check_rate_limit(&self, key: Option<&str>) -> bool {
        if !self.config.enabled {
            return true;
        }

        // Check global limit first
        if let Some(global) = &self.global_limiter {
            if !global.try_acquire(1).await {
                return false;
            }
        }

        // Check per-key limit
        if let Some(key) = key {
            let mut limiters = self.key_limiters.lock().await;
            let limiter = limiters.entry(key.to_string()).or_insert_with(|| {
                Arc::new(KeyRateLimiter::new(
                    self.config.burst_size.unwrap_or(10),
                    self.config.requests_per_second.unwrap_or(10),
                ))
            });
            limiter.try_acquire(1).await
        } else {
            true
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::time::sleep;

    #[tokio::test]
    async fn test_token_bucket_basic() {
        let mut bucket = TokenBucket::new(5, 1);
        assert!(bucket.try_acquire(3));
        assert!(bucket.try_acquire(1));
        assert!(!bucket.try_acquire(2)); // Would exceed remaining
    }

    #[tokio::test]
    async fn test_rate_limiter_disabled() {
        let limiter = RateLimiter::new(RateLimitConfig::default());
        assert!(limiter.check_rate_limit(None).await);
    }

    #[tokio::test]
    async fn test_rate_limiter_enabled() {
        let config = RateLimitConfig {
            requests_per_second: Some(10),
            burst_size: Some(5),
            enabled: true,
        };
        let limiter = RateLimiter::new(config);

        // Should allow burst_size requests
        for _ in 0..5 {
            assert!(limiter.check_rate_limit(None).await);
        }

        // 6th request should be denied (burst exhausted)
        assert!(!limiter.check_rate_limit(None).await);
    }
}
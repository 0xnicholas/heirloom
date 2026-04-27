use std::time::Duration;
use tokio::time::sleep;
use rand::Rng;
use crate::error::{ErrorKind, GatewayError};

pub struct RetryPolicy {
    max_retries: u32,
    backoff_initial: Duration,
    backoff_max: Duration,
}

impl RetryPolicy {
    pub fn new(max_retries: u32, backoff_initial: Duration, backoff_max: Duration) -> Self {
        Self {
            max_retries,
            backoff_initial,
            backoff_max,
        }
    }
    
    pub fn max_retries(&self) -> u32 {
        self.max_retries
    }
    
    pub fn backoff_initial(&self) -> Duration {
        self.backoff_initial
    }
    
    pub fn backoff_max(&self) -> Duration {
        self.backoff_max
    }
    
    pub async fn execute<F, Fut, T>(&self,
        operation: F,
    ) -> Result<T, GatewayError>
    where
        F: Fn() -> Fut,
        Fut: std::future::Future<Output = Result<T, GatewayError>>,
    {
        let mut last_error = None;
        
        for attempt in 0..=self.max_retries {
            match operation().await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    if !Self::is_retryable(&e) || attempt == self.max_retries {
                        return Err(e);
                    }
                    last_error = Some(e);
                    let backoff = self.calculate_backoff(attempt);
                    sleep(backoff).await;
                }
            }
        }
        
        Err(last_error.unwrap_or(GatewayError::new(ErrorKind::MaxRetriesExceeded, "Max retries exceeded")))
    }
    
    pub fn is_retryable(error: &GatewayError) -> bool {
        matches!(error.kind,
            ErrorKind::Network |
            ErrorKind::RateLimited |
            ErrorKind::MaxRetriesExceeded
        ) || matches!(error.status_code, Some(500..=599)) || matches!(error.status_code, Some(429))
    }
    
    fn calculate_backoff(&self, attempt: u32) -> Duration {
        let base = self.backoff_initial.as_millis() as u64 * 2u64.pow(attempt);
        let capped = std::cmp::min(base, self.backoff_max.as_millis() as u64);
        let jitter = rand::thread_rng().gen_range(0..capped / 10);
        Duration::from_millis(capped + jitter)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    #[tokio::test]
    async fn test_retry_succeeds_eventually() {
        let policy = RetryPolicy::new(3, Duration::from_millis(10), Duration::from_millis(100));
        let attempts = Arc::new(AtomicUsize::new(0));
        let attempts_clone = attempts.clone();
        
        let result = policy.execute(|| async {
            let attempt = attempts_clone.fetch_add(1, Ordering::SeqCst);
            if attempt < 2 {
                Err(GatewayError::new(ErrorKind::Network, "fail".to_string()))
            } else {
                Ok("success")
            }
        }).await;
        
        assert_eq!(result.unwrap(), "success");
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn test_no_retry_on_4xx() {
        let policy = RetryPolicy::new(3, Duration::from_millis(10), Duration::from_millis(100));
        let attempts = Arc::new(AtomicUsize::new(0));
        let attempts_clone = attempts.clone();
        
        let result: Result<String, _> = policy.execute(|| async {
            attempts_clone.fetch_add(1, Ordering::SeqCst);
            Err(GatewayError::new(ErrorKind::InvalidRequest, "bad request".to_string()))
        }).await;
        
        assert!(result.is_err());
        assert_eq!(attempts.load(Ordering::SeqCst), 1); // No retries
    }
}

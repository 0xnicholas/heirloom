use thiserror::Error;

#[derive(Error, Debug, Clone)]
pub enum ErrorKind {
    #[error("provider error")]
    Provider,
    #[error("network error")]
    Network,
    #[error("rate limited")]
    RateLimited,
    #[error("invalid request")]
    InvalidRequest,
    #[error("authentication failed")]
    AuthenticationFailed,
    #[error("no provider available")]
    NoProviderAvailable,
    #[error("max retries exceeded")]
    MaxRetriesExceeded,
}

#[derive(Debug, Clone)]
pub struct GatewayError {
    pub kind: ErrorKind,
    pub message: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub status_code: Option<u16>,
    pub retry_count: Option<u32>,
    pub fallback_index: Option<u32>,
}

impl GatewayError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            provider: None,
            model: None,
            status_code: None,
            retry_count: None,
            fallback_index: None,
        }
    }

    pub fn with_provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }

    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    pub fn with_status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }

    pub fn with_retry_count(mut self, count: u32) -> Self {
        self.retry_count = Some(count);
        self
    }

    pub fn with_fallback_index(mut self, index: u32) -> Self {
        self.fallback_index = Some(index);
        self
    }

    pub fn status_code(&self) -> u16 {
        match self.kind {
            ErrorKind::InvalidRequest => 400,
            ErrorKind::AuthenticationFailed => 401,
            ErrorKind::RateLimited => 429,
            ErrorKind::NoProviderAvailable => 503,
            _ => self.status_code.unwrap_or(500),
        }
    }

    pub fn is_retryable(&self) -> bool {
        matches!(
            self.kind,
            ErrorKind::Network | ErrorKind::RateLimited | ErrorKind::MaxRetriesExceeded
        ) || matches!(self.status_code, Some(500..=599 | 429))
    }
}

impl std::fmt::Display for GatewayError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)?;
        if let Some(provider) = &self.provider {
            write!(f, " [provider={}]", provider)?;
        }
        if let Some(model) = &self.model {
            write!(f, " [model={}]", model)?;
        }
        if let Some(retry) = self.retry_count {
            write!(f, " [retry={}]", retry)?;
        }
        if let Some(fallback) = self.fallback_index {
            write!(f, " [fallback={}]", fallback)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_builder() {
        let err = GatewayError::new(ErrorKind::Provider, "test error")
            .with_provider("openai")
            .with_model("gpt-4")
            .with_status_code(500)
            .with_retry_count(2);

        assert_eq!(err.status_code(), 500);
        assert!(err.is_retryable());
        assert!(err.to_string().contains("[provider=openai]"));
    }

    #[test]
    fn test_non_retryable_error() {
        let err = GatewayError::new(ErrorKind::InvalidRequest, "bad request");
        assert!(!err.is_retryable());
        assert_eq!(err.status_code(), 400);
    }
}

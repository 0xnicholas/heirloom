use std::sync::atomic::{AtomicU32, Ordering};
use uuid::Uuid;

#[derive(Debug)]
pub struct RequestContext {
    pub request_id: String,
    pub provider_name: String,
    pub model: String,
    pub retry_count: AtomicU32,
    pub fallback_index: AtomicU32,
}

impl RequestContext {
    pub fn new(provider_name: impl Into<String>, model: impl Into<String>) -> Self {
        Self {
            request_id: Uuid::new_v4().to_string(),
            provider_name: provider_name.into(),
            model: model.into(),
            retry_count: AtomicU32::new(0),
            fallback_index: AtomicU32::new(0),
        }
    }
    
    pub fn increment_retry(&self) -> u32 {
        self.retry_count.fetch_add(1, Ordering::SeqCst)
    }
    
    pub fn get_retry_count(&self) -> u32 {
        self.retry_count.load(Ordering::SeqCst)
    }
    
    pub fn set_fallback_index(&self, index: u32) {
        self.fallback_index.store(index, Ordering::SeqCst);
    }
    
    pub fn get_fallback_index(&self) -> u32 {
        self.fallback_index.load(Ordering::SeqCst)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_context_creation() {
        let ctx = RequestContext::new("openai", "gpt-4");
        assert_eq!(ctx.provider_name, "openai");
        assert_eq!(ctx.model, "gpt-4");
        assert!(!ctx.request_id.is_empty());
    }

    #[test]
    fn test_retry_tracking() {
        let ctx = RequestContext::new("openai", "gpt-4");
        assert_eq!(ctx.get_retry_count(), 0);
        ctx.increment_retry();
        assert_eq!(ctx.get_retry_count(), 1);
    }
}

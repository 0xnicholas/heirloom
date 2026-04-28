use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

/// Simple in-memory metrics collector
#[derive(Default)]
pub struct Metrics {
    pub requests_total: AtomicU64,
    pub requests_success: AtomicU64,
    pub requests_error: AtomicU64,
    pub latency_ms_sum: AtomicU64,
    pub latency_ms_count: AtomicU64,
    
    // Per-provider counters
    pub provider_requests: std::sync::Mutex<std::collections::HashMap<String, AtomicU64>>,
}

impl Metrics {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn record_request(&self,
        provider: &str,
        latency_ms: u64,
        success: bool,
    ) {
        self.requests_total.fetch_add(1, Ordering::Relaxed);
        self.latency_ms_sum.fetch_add(latency_ms, Ordering::Relaxed);
        self.latency_ms_count.fetch_add(1, Ordering::Relaxed);

        if success {
            self.requests_success.fetch_add(1, Ordering::Relaxed);
        } else {
            self.requests_error.fetch_add(1, Ordering::Relaxed);
        }

        // Update provider-specific counter
        if let Ok(mut providers) = self.provider_requests.lock() {
            providers
                .entry(provider.to_string())
                .or_insert_with(|| AtomicU64::new(0))
                .fetch_add(1, Ordering::Relaxed);
        }
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        let total = self.requests_total.load(Ordering::Relaxed);
        let success = self.requests_success.load(Ordering::Relaxed);
        let error = self.requests_error.load(Ordering::Relaxed);
        let latency_sum = self.latency_ms_sum.load(Ordering::Relaxed);
        let latency_count = self.latency_ms_count.load(Ordering::Relaxed);

        let avg_latency = if latency_count > 0 {
            latency_sum / latency_count
        } else {
            0
        };

        let provider_requests = if let Ok(providers) = self.provider_requests.lock() {
            providers
                .iter()
                .map(|(k, v)| (k.clone(), v.load(Ordering::Relaxed)))
                .collect()
        } else {
            std::collections::HashMap::new()
        };

        MetricsSnapshot {
            total,
            success,
            error,
            avg_latency_ms: avg_latency,
            provider_requests,
        }
    }
}

#[derive(Debug, serde::Serialize)]
pub struct MetricsSnapshot {
    pub total: u64,
    pub success: u64,
    pub error: u64,
    pub avg_latency_ms: u64,
    pub provider_requests: std::collections::HashMap<String, u64>,
}

/// Middleware to collect request metrics
pub struct MetricsMiddleware;

impl<S, B> actix_web::dev::Transform<S, actix_web::dev::ServiceRequest> for MetricsMiddleware
where
    S: actix_web::dev::Service<
            actix_web::dev::ServiceRequest,
            Response = actix_web::dev::ServiceResponse<B>,
            Error = actix_web::Error,
        >,
    S::Future: 'static,
    B: 'static,
{
    type Response = actix_web::dev::ServiceResponse<B>;
    type Error = actix_web::Error;
    type Transform = MetricsMiddlewareService<S>;
    type InitError = ();
    type Future = std::future::Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(
        &self,
        service: S,
    ) -> Self::Future {
        std::future::ready(Ok(MetricsMiddlewareService { service }))
    }
}

pub struct MetricsMiddlewareService<S> {
    service: S,
}

impl<S, B> actix_web::dev::Service<actix_web::dev::ServiceRequest>
    for MetricsMiddlewareService<S>
where
    S: actix_web::dev::Service<
            actix_web::dev::ServiceRequest,
            Response = actix_web::dev::ServiceResponse<B>,
            Error = actix_web::Error,
        >,
    S::Future: 'static,
    B: 'static,
{
    type Response = actix_web::dev::ServiceResponse<B>;
    type Error = actix_web::Error;
    type Future = std::pin::Pin<
        Box<
            dyn std::future::Future<Output = Result<Self::Response, Self::Error>> + 'static,
        >,
    >;

    fn poll_ready(
        &self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        self.service.poll_ready(cx)
    }

    fn call(&self,
        req: actix_web::dev::ServiceRequest,
    ) -> Self::Future {
        let start = Instant::now();
        let fut = self.service.call(req);

        Box::pin(async move {
            let res = fut.await?;
            let latency = start.elapsed().as_millis() as u64;
            let status = res.status();
            let success = status.is_success();

            // Extract provider from request path or headers
            let provider = "unknown".to_string();

            tracing::info!(
                "request_metrics provider={} latency_ms={} status={} success={}",
                provider,
                latency,
                status.as_u16(),
                success
            );

            Ok(res)
        })
    }
}
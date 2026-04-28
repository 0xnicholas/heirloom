use actix_web::{
    dev::{Service, Transform},
    http, Error, HttpResponse,
};
use std::future::{ready, Ready};
use std::pin::Pin;
use std::task::{Context, Poll};

pub struct ApiKeyAuth {
    keys: Vec<String>,
}

impl ApiKeyAuth {
    pub fn new(keys: Vec<String>) -> Self {
        Self { keys }
    }
}

impl<S, B> Transform<S, actix_web::dev::ServiceRequest> for ApiKeyAuth
where
    S: Service<
        actix_web::dev::ServiceRequest,
        Response = actix_web::dev::ServiceResponse<B>,
        Error = Error,
    >,
    S::Future: 'static,
    B: 'static,
{
    type Response = actix_web::dev::ServiceResponse<B>;
    type Error = Error;
    type Transform = ApiKeyAuthMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(ApiKeyAuthMiddleware {
            service,
            keys: self.keys.clone(),
        }))
    }
}

pub struct ApiKeyAuthMiddleware<S> {
    service: S,
    keys: Vec<String>,
}

impl<S, B> Service<actix_web::dev::ServiceRequest> for ApiKeyAuthMiddleware<S>
where
    S: Service<
        actix_web::dev::ServiceRequest,
        Response = actix_web::dev::ServiceResponse<B>,
        Error = Error,
    >,
    S::Future: 'static,
    B: 'static,
{
    type Response = actix_web::dev::ServiceResponse<B>;
    type Error = Error;
    type Future =
        Pin<Box<dyn std::future::Future<Output = Result<Self::Response, Self::Error>> + 'static>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.poll_ready(cx)
    }

    fn call(&self, req: actix_web::dev::ServiceRequest) -> Self::Future {
        // Skip auth if no keys configured
        if self.keys.is_empty() {
            let fut = self.service.call(req);
            return Box::pin(async move { fut.await });
        }

        // Check Authorization header
        let auth_header = req
            .headers()
            .get(http::header::AUTHORIZATION)
            .and_then(|h| h.to_str().ok());

        let is_valid = match auth_header {
            Some(header) if header.starts_with("Bearer ") => {
                let key = &header[7..];
                self.keys.iter().any(|k| k == key)
            }
            _ => false,
        };

        if !is_valid {
            return Box::pin(async move {
                Err(actix_web::error::ErrorUnauthorized(
                    serde_json::json!({
                        "error": {
                            "message": "Invalid or missing API key",
                            "type": "authentication_error",
                            "code": "invalid_api_key"
                        }
                    })
                    .to_string(),
                ))
            });
        }

        let fut = self.service.call(req);
        Box::pin(async move { fut.await })
    }
}

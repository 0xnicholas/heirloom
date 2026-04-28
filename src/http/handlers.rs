use actix_web::{web, HttpRequest, HttpResponse, Responder};
use bytes::Bytes;
use futures::stream::StreamExt;
use std::sync::Arc;

use crate::context::RequestContext;
use crate::gateway::Gateway;
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;
use crate::types::*;

pub async fn chat_completions(
    req: HttpRequest,
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Arc<Gateway>>,
    mcp_manager: web::Data<Option<Arc<MCPClientManager>>>,
    agent_executor: web::Data<Option<Arc<AgentExecutor>>>,
) -> impl Responder {
    let request = body.into_inner();
    let ctx = RequestContext::new("unknown", &request.model);

    tracing::info!(
        "request_id={} model={} stream={}",
        ctx.request_id,
        request.model,
        request.stream
    );

    // Check if MCP is enabled and has tools
    if let (Some(mcp), Some(agent)) = (mcp_manager.as_ref(), agent_executor.as_ref()) {
        let tools = mcp.get_tools().await;
        if !tools.is_empty() {
            // Use agent mode
            match agent.execute(request).await {
                Ok(response) => return HttpResponse::Ok().json(response),
                Err(e) => {
                    let status = e.status_code();
                    tracing::error!("request_id={} agent_error: {}", ctx.request_id, e);
                    return HttpResponse::build(
                        actix_web::http::StatusCode::from_u16(status).unwrap(),
                    )
                    .json(ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "agent_error".to_string(),
                            code: Some(status.to_string()),
                        },
                    });
                }
            }
        }
    }

    // Standard mode
    if request.stream {
        match gateway.chat_completion_stream(request).await {
            Ok(stream) => {
                let sse_stream = stream
                    .map(|chunk| match chunk {
                        Ok(chunk) => {
                            let data = serde_json::to_string(&chunk).unwrap();
                            Ok::<_, actix_web::Error>(Bytes::from(format!("data: {}\n\n", data)))
                        }
                        Err(e) => {
                            let data = serde_json::json!({"error": e.to_string()}).to_string();
                            Ok(Bytes::from(format!("data: {}\n\n", data)))
                        }
                    })
                    .chain(futures::stream::once(async {
                        Ok::<_, actix_web::Error>(Bytes::from_static(b"data: [DONE]\n\n"))
                    }));

                HttpResponse::Ok()
                    .content_type("text/event-stream")
                    .insert_header(("Cache-Control", "no-cache"))
                    .insert_header(("Connection", "keep-alive"))
                    .streaming(sse_stream)
            }
            Err(e) => {
                let status = e.status_code();
                HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap()).json(
                    ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "server_error".to_string(),
                            code: Some(status.to_string()),
                        },
                    },
                )
            }
        }
    } else {
        match gateway.chat_completion(request).await {
            Ok(response) => HttpResponse::Ok().json(response),
            Err(e) => {
                let status = e.status_code();
                HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap()).json(
                    ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "provider_error".to_string(),
                            code: Some(status.to_string()),
                        },
                    },
                )
            }
        }
    }
}

pub async fn embeddings(
    req: HttpRequest,
    body: web::Json<EmbeddingRequest>,
    gateway: web::Data<Arc<Gateway>>,
) -> impl Responder {
    let request = body.into_inner();
    let ctx = RequestContext::new("unknown", &request.model);

    tracing::info!(
        "request_id={} model={} embedding_request",
        ctx.request_id,
        request.model
    );

    match gateway.embedding(request).await {
        Ok(response) => HttpResponse::Ok().json(response),
        Err(e) => {
            let status = e.status_code();
            HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap()).json(
                ErrorResponse {
                    error: ApiError {
                        message: e.to_string(),
                        error_type: "provider_error".to_string(),
                        code: Some(status.to_string()),
                    },
                },
            )
        }
    }
}

pub async fn list_models(gateway: web::Data<Arc<Gateway>>) -> impl Responder {
    match gateway.list_models().await {
        Ok(response) => HttpResponse::Ok().json(response),
        Err(e) => {
            let status = e.status_code();
            HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap()).json(
                ErrorResponse {
                    error: ApiError {
                        message: e.to_string(),
                        error_type: "provider_error".to_string(),
                        code: Some(status.to_string()),
                    },
                },
            )
        }
    }
}

use crate::http::metrics::Metrics;

pub async fn health() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "ok"
    }))
}

pub async fn metrics_endpoint(
    metrics: web::Data<Arc<Metrics>>,
) -> impl Responder {
    let snapshot = metrics.snapshot();
    HttpResponse::Ok().json(snapshot)
}

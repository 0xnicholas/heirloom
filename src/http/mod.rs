use actix_web::{web, App, HttpServer};
use std::sync::Arc;

use crate::config::AppConfig;
use crate::gateway::Gateway;
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;

pub mod handlers;
pub mod middleware;

pub async fn run_server(
    config: &AppConfig,
    gateway: Arc<Gateway>,
    mcp_manager: Option<Arc<MCPClientManager>>,
    agent_executor: Option<Arc<AgentExecutor>>,
) -> std::io::Result<()> {
    let gateway = web::Data::new(gateway);
    let mcp_manager = web::Data::new(mcp_manager);
    let agent_executor = web::Data::new(agent_executor);
    let bind_addr = format!("{}:{}", config.server.host, config.server.port);
    
    HttpServer::new(move || {
        App::new()
            .wrap(actix_web::middleware::Logger::default())
            .wrap(middleware::configure_cors())
            .wrap(middleware::RequestIdMiddleware)
            .app_data(gateway.clone())
            .app_data(mcp_manager.clone())
            .app_data(agent_executor.clone())
            .route("/v1/chat/completions", web::post().to(handlers::chat_completions))
            .route("/v1/embeddings", web::post().to(handlers::embeddings))
            .route("/v1/models", web::get().to(handlers::list_models))
            .route("/health", web::get().to(handlers::health))
    })
    .bind(&bind_addr)?
    .run()
    .await
}

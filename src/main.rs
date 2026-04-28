use std::path::Path;
use std::sync::Arc;
use tracing::{error, info};

mod client;
mod config;
mod context;
mod error;
mod gateway;
mod http;
mod mcp;
mod provider;
mod types;

use config::AppConfig;
use gateway::Gateway;
use mcp::agent::AgentExecutor;
use mcp::client::MCPClientManager;

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        error!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    let config_path =
        std::env::var("HEIRLOOM_CONFIG_PATH").unwrap_or_else(|_| "config.toml".to_string());

    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    config.validate()?;

    tracing_subscriber::fmt()
        .with_env_filter(&config.server.log_level)
        .init();

    info!("Loading configuration from {}", config_path);
    info!(
        "Starting Heirloom on {}:{}",
        config.server.host, config.server.port
    );

    let gateway = Arc::new(Gateway::from_config(&config)?);

    // Initialize MCP if configured
    let (mcp_manager, agent_executor) = if let Some(mcp_config) = &config.mcp {
        if mcp_config.enabled {
            info!("Initializing MCP with {} clients", mcp_config.clients.len());
            let mcp_manager = Arc::new(MCPClientManager::new());

            for client_config in &mcp_config.clients {
                match mcp_manager.add_client(client_config).await {
                    Ok(_) => info!("MCP client '{}' initialized", client_config.name),
                    Err(e) => tracing::warn!(
                        "Failed to initialize MCP client '{}': {}",
                        client_config.name,
                        e
                    ),
                }
            }

            let agent_executor = Arc::new(AgentExecutor::new(
                mcp_config.max_agent_depth,
                gateway.clone(),
                mcp_manager.clone(),
            ));

            (Some(mcp_manager), Some(agent_executor))
        } else {
            (None, None)
        }
    } else {
        (None, None)
    };

    let server_handle =
        http::run_server(&config, gateway.clone(), mcp_manager, agent_executor).await?;

    info!("Server started, waiting for shutdown signal...");

    // Wait for shutdown signal
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {
            info!("Received SIGINT, shutting down gracefully...");
        }
        _ = async {
            // SIGTERM handler for Unix systems
            #[cfg(unix)]
            {
                use tokio::signal::unix::{signal, SignalKind};
                let mut sigterm = signal(SignalKind::terminate())
                    .expect("Failed to create SIGTERM handler");
                sigterm.recv().await;
            }
            #[cfg(not(unix))]
            {
                std::future::pending::<()>().await;
            }
        } => {
            info!("Received SIGTERM, shutting down gracefully...");
        }
    }

    // Trigger graceful shutdown
    server_handle.shutdown();

    // Give in-flight requests time to complete (max 5 seconds)
    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;

    info!("Shutdown complete");
    Ok(())
}

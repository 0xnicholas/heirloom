use tracing::{info, error};
use std::path::Path;
use std::sync::Arc;

mod config;
mod context;
mod error;
mod types;
mod provider;
mod client;
mod gateway;
mod http;
mod mcp;

use config::AppConfig;
use gateway::Gateway;
use mcp::client::MCPClientManager;
use mcp::agent::AgentExecutor;

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        error!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    let config_path = std::env::var("HEIRLOOM_CONFIG_PATH")
        .unwrap_or_else(|_| "config.toml".to_string());
    
    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    config.validate()?;
    
    tracing_subscriber::fmt()
        .with_env_filter(&config.server.log_level)
        .init();
    
    info!("Loading configuration from {}", config_path);
    info!("Starting Heirloom on {}:{}", config.server.host, config.server.port);
    
    let gateway = Arc::new(Gateway::from_config(&config)?);
    
    // Initialize MCP if configured
    let (mcp_manager, agent_executor) = if let Some(mcp_config) = &config.mcp {
        if mcp_config.enabled {
            info!("Initializing MCP with {} clients", mcp_config.clients.len());
            let mcp_manager = Arc::new(MCPClientManager::new());
            
            for client_config in &mcp_config.clients {
                match mcp_manager.add_client(client_config).await {
                    Ok(_) => info!("MCP client '{}' initialized", client_config.name),
                    Err(e) => tracing::warn!("Failed to initialize MCP client '{}': {}", client_config.name, e),
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
    
    http::run_server(
        &config,
        gateway,
        mcp_manager,
        agent_executor,
    ).await?;
    
    Ok(())
}

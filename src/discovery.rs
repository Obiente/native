use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::schema::AppIdentity;

/// Normalized facts gathered from a Nextcloud server before semantic inference.
///
/// Raw HTML is deliberately absent. A later discovery service may contribute
/// accessibility or network observations as typed evidence, but the native
/// runtime never needs to embed the remote app.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoverySnapshot {
    pub app: AppIdentity,
    #[serde(default)]
    pub navigation: Vec<NavigationEntry>,
    #[serde(default)]
    pub capabilities: Value,
    pub openapi: Value,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NavigationEntry {
    pub id: String,
    pub name: String,
    pub route: String,
    pub icon_url: Option<String>,
}

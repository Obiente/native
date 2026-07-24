use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;
use url::Url;

use crate::{ActionIntent, ActionRisk, AppIdentity, Confidence, FieldKind, HttpMethod};

pub const DYNAMIC_APP_DESCRIPTOR_VERSION: &str = "1.0";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicAppDescriptor {
    pub descriptor_version: String,
    pub app: AppIdentity,
    pub endpoint_policy: EndpointPolicy,
    #[serde(default)]
    pub capabilities: Vec<CapabilityFact>,
    #[serde(default)]
    pub permissions: Vec<PermissionSpec>,
    #[serde(default)]
    pub resources: Vec<DynamicResource>,
    #[serde(default)]
    pub layouts: Vec<DynamicLayout>,
    #[serde(default)]
    pub links: Vec<DynamicLink>,
    #[serde(default)]
    pub forms: Vec<DynamicForm>,
    #[serde(default)]
    pub actions: Vec<DynamicAction>,
    #[serde(default)]
    pub warnings: Vec<DynamicWarning>,
}

impl DynamicAppDescriptor {
    pub fn empty(app: AppIdentity, endpoint_policy: EndpointPolicy) -> Self {
        Self {
            descriptor_version: DYNAMIC_APP_DESCRIPTOR_VERSION.to_owned(),
            app,
            endpoint_policy,
            capabilities: Vec::new(),
            permissions: Vec::new(),
            resources: Vec::new(),
            layouts: Vec::new(),
            links: Vec::new(),
            forms: Vec::new(),
            actions: Vec::new(),
            warnings: Vec::new(),
        }
    }

    /// Revalidates the serialized trust boundary before a runtime accepts it.
    pub fn validate(&self) -> Result<(), DescriptorValidationError> {
        if self.descriptor_version != DYNAMIC_APP_DESCRIPTOR_VERSION {
            return Err(DescriptorValidationError::UnsupportedVersion(
                self.descriptor_version.clone(),
            ));
        }
        self.endpoint_policy.validate()?;

        ensure_unique(
            self.resources.iter().map(|item| item.id.as_str()),
            "resource",
        )?;
        ensure_unique(self.layouts.iter().map(|item| item.id.as_str()), "layout")?;
        ensure_unique(self.links.iter().map(|item| item.id.as_str()), "link")?;
        ensure_unique(self.forms.iter().map(|item| item.id.as_str()), "form")?;
        ensure_unique(self.actions.iter().map(|item| item.id.as_str()), "action")?;
        ensure_unique(
            self.permissions.iter().map(|item| item.id.as_str()),
            "permission",
        )?;
        ensure_unique(
            self.capabilities.iter().map(|item| item.id.as_str()),
            "capability",
        )?;

        for resource in &self.resources {
            ensure_unique(
                resource.fields.iter().map(|field| field.id.as_str()),
                "field",
            )?;
            for capability_id in &resource.capability_ids {
                if !self
                    .capabilities
                    .iter()
                    .any(|item| &item.id == capability_id)
                {
                    return Err(DescriptorValidationError::MissingReference {
                        kind: "capability",
                        id: capability_id.clone(),
                    });
                }
            }
            for permission_id in &resource.permission_ids {
                if !self
                    .permissions
                    .iter()
                    .any(|item| &item.id == permission_id)
                {
                    return Err(DescriptorValidationError::MissingReference {
                        kind: "permission",
                        id: permission_id.clone(),
                    });
                }
            }
        }
        for layout in &self.layouts {
            let resource = self.resource(&layout.resource_id)?;
            for field in &layout.fields {
                require_field(resource, &field.field_id)?;
            }
            if let Some(action_id) = &layout.source_action_id {
                self.action(action_id)?;
            }
        }
        for link in &self.links {
            let resource = self.resource(&link.resource_id)?;
            require_field(resource, &link.source_field_id)?;
            if let DynamicLinkTarget::Action { action_id } = &link.target {
                self.action(action_id)?;
            }
        }
        for form in &self.forms {
            let action = self.action(&form.action_id)?;
            self.resource(&form.resource_id)?;
            if action.resource_id != form.resource_id {
                return Err(DescriptorValidationError::MismatchedReference {
                    kind: "form action resource",
                    id: form.id.clone(),
                });
            }
            if action.binding.method.is_read_only() {
                return Err(DescriptorValidationError::ReadActionForm(form.id.clone()));
            }
        }
        for action in &self.actions {
            self.resource(&action.resource_id)?;
            self.endpoint_policy
                .validate_api_path(&action.binding.path)?;
            ensure_unique(
                action
                    .binding
                    .path_parameters
                    .iter()
                    .map(|item| item.name.as_str()),
                "path parameter",
            )?;
            ensure_unique(
                action
                    .binding
                    .query_parameters
                    .iter()
                    .map(|item| item.name.as_str()),
                "query parameter",
            )?;
            let placeholders = path_placeholders(&action.binding.path);
            let parameters = action
                .binding
                .path_parameters
                .iter()
                .map(|item| item.name.as_str())
                .collect::<BTreeSet<_>>();
            if placeholders != parameters {
                return Err(DescriptorValidationError::MismatchedReference {
                    kind: "path parameter",
                    id: action.id.clone(),
                });
            }
            for permission_id in &action.permission_ids {
                if !self
                    .permissions
                    .iter()
                    .any(|item| &item.id == permission_id)
                {
                    return Err(DescriptorValidationError::MissingReference {
                        kind: "permission",
                        id: permission_id.clone(),
                    });
                }
            }
            for capability_id in &action.capability_ids {
                if !self
                    .capabilities
                    .iter()
                    .any(|item| &item.id == capability_id)
                {
                    return Err(DescriptorValidationError::MissingReference {
                        kind: "capability",
                        id: capability_id.clone(),
                    });
                }
            }
            if !action.binding.method.is_read_only()
                && action.provenance.iter().all(|item| {
                    !matches!(
                        item.kind,
                        ProvenanceKind::AdvertisedOpenApi
                            | ProvenanceKind::VerifiedAdapter
                            | ProvenanceKind::VerifiedAppPackage
                            | ProvenanceKind::AppStoreLinkedSourceTag
                    )
                })
            {
                return Err(DescriptorValidationError::UnprovenWrite(action.id.clone()));
            }
        }
        Ok(())
    }

    fn resource(&self, id: &str) -> Result<&DynamicResource, DescriptorValidationError> {
        self.resources
            .iter()
            .find(|item| item.id == id)
            .ok_or_else(|| DescriptorValidationError::MissingReference {
                kind: "resource",
                id: id.to_owned(),
            })
    }

    fn action(&self, id: &str) -> Result<&DynamicAction, DescriptorValidationError> {
        self.actions
            .iter()
            .find(|item| item.id == id)
            .ok_or_else(|| DescriptorValidationError::MissingReference {
                kind: "action",
                id: id.to_owned(),
            })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EndpointPolicy {
    pub server_origin: String,
    #[serde(default)]
    pub approved_api_prefixes: Vec<String>,
}

impl EndpointPolicy {
    pub fn validate(&self) -> Result<(), DescriptorValidationError> {
        let origin = parse_origin(&self.server_origin)?;
        if origin.path() != "/" || origin.query().is_some() || origin.fragment().is_some() {
            return Err(DescriptorValidationError::InvalidOrigin(
                self.server_origin.clone(),
            ));
        }
        if self.approved_api_prefixes.is_empty() {
            return Err(DescriptorValidationError::NoApprovedPrefixes);
        }
        for prefix in &self.approved_api_prefixes {
            validate_relative_path(prefix)?;
        }
        Ok(())
    }

    pub fn validate_same_origin_url(&self, value: &str) -> Result<(), DescriptorValidationError> {
        let origin = parse_origin(&self.server_origin)?;
        let candidate = origin
            .join(value)
            .map_err(|_| DescriptorValidationError::InvalidEndpoint(value.to_owned()))?;
        if candidate.origin() != origin.origin() {
            return Err(DescriptorValidationError::CrossOrigin(value.to_owned()));
        }
        if !matches!(candidate.scheme(), "http" | "https")
            || !candidate.username().is_empty()
            || candidate.password().is_some()
        {
            return Err(DescriptorValidationError::InvalidEndpoint(value.to_owned()));
        }
        Ok(())
    }

    pub fn validate_api_path(&self, path: &str) -> Result<(), DescriptorValidationError> {
        validate_relative_path(path)?;
        self.validate_same_origin_url(path)?;
        if !self
            .approved_api_prefixes
            .iter()
            .any(|prefix| path_matches_prefix(path, prefix))
        {
            return Err(DescriptorValidationError::UnapprovedEndpoint(
                path.to_owned(),
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityFact {
    pub id: String,
    pub value: Value,
    pub confidence: Confidence,
    pub provenance: Provenance,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PermissionSpec {
    pub id: String,
    pub label: String,
    pub kind: PermissionKind,
    pub state: PermissionState,
    pub confidence: Confidence,
    pub provenance: Provenance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PermissionKind {
    AuthenticatedSession,
    ApiScope,
    ServerRole,
    ResourceAcl,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PermissionState {
    Required,
    Granted,
    Denied,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicResource {
    pub id: String,
    pub label: String,
    pub collection: bool,
    #[serde(default)]
    pub fields: Vec<DynamicField>,
    #[serde(default)]
    pub capability_ids: Vec<String>,
    #[serde(default)]
    pub permission_ids: Vec<String>,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicField {
    pub id: String,
    pub label: String,
    pub kind: FieldKind,
    pub required: bool,
    pub read_only: bool,
    pub nullable: bool,
    pub multiple: bool,
    pub format: Option<String>,
    pub enum_values: Option<Vec<String>>,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicLayout {
    pub id: String,
    pub title: String,
    pub resource_id: String,
    pub kind: LayoutKind,
    #[serde(default)]
    pub fields: Vec<LayoutField>,
    pub source_action_id: Option<String>,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LayoutKind {
    List,
    Detail,
    Grid,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LayoutField {
    pub field_id: String,
    pub role: LayoutFieldRole,
    pub visible: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LayoutFieldRole {
    Identity,
    Title,
    Subtitle,
    Body,
    Image,
    Metadata,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicLink {
    pub id: String,
    pub label: String,
    pub resource_id: String,
    pub source_field_id: String,
    pub target: DynamicLinkTarget,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum DynamicLinkTarget {
    FieldUrl { allow_external: bool },
    Action { action_id: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicForm {
    pub id: String,
    pub title: String,
    pub resource_id: String,
    pub action_id: String,
    #[serde(default)]
    pub fields: Vec<FormField>,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FormField {
    pub field_id: String,
    pub label: String,
    pub kind: FieldKind,
    pub required: bool,
    pub format: Option<String>,
    pub enum_values: Option<Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicAction {
    pub id: String,
    pub label: String,
    pub resource_id: String,
    pub intent: ActionIntent,
    pub risk: ActionRisk,
    pub requires_confirmation: bool,
    pub binding: DynamicHttpBinding,
    #[serde(default)]
    pub capability_ids: Vec<String>,
    #[serde(default)]
    pub permission_ids: Vec<String>,
    pub confidence: Confidence,
    #[serde(default)]
    pub provenance: Vec<Provenance>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicHttpBinding {
    pub method: HttpMethod,
    pub path: String,
    #[serde(default)]
    pub path_parameters: Vec<HttpParameter>,
    #[serde(default)]
    pub query_parameters: Vec<HttpParameter>,
    pub body: Option<HttpBody>,
    #[serde(default)]
    pub auth: Vec<AuthRequirement>,
    pub ocs: Option<OcsMetadata>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpParameter {
    pub name: String,
    pub required: bool,
    pub schema: Value,
    pub source: ParameterSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ParameterSource {
    UserInput,
    ResourceField,
    RuntimeContext,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpBody {
    pub content_type: String,
    pub required: bool,
    pub schema: Value,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthRequirement {
    pub scheme: String,
    pub kind: AuthKind,
    #[serde(default)]
    pub scopes: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum AuthKind {
    NextcloudSession,
    Basic,
    Bearer,
    Cookie,
    ApiKey,
    OAuth2,
    OpenIdConnect,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OcsMetadata {
    pub api_request_header: bool,
    pub response_data_pointer: String,
    pub response_meta_pointer: String,
    pub format_query_parameter: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Provenance {
    pub kind: ProvenanceKind,
    pub source: String,
    pub detail: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ProvenanceKind {
    AppMetadata,
    Capability,
    AdvertisedOpenApi,
    SuccessfulReadObservation,
    VerifiedAdapter,
    VerifiedAppPackage,
    AppStoreLinkedSourceTag,
    DeterministicInference,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicWarning {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicDiscoveryInput {
    pub app: AppIdentity,
    pub endpoint_policy: EndpointPolicy,
    #[serde(default)]
    pub capabilities: Vec<CapabilityFact>,
    #[serde(rename = "advertisedOpenApi")]
    pub advertised_openapi: Option<AdvertisedOpenApi>,
    #[serde(default)]
    pub successful_reads: Vec<SuccessfulReadObservation>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdvertisedOpenApi {
    pub document_url: String,
    pub document: Value,
    #[serde(default)]
    pub trust: OpenApiTrust,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum OpenApiTrust {
    #[default]
    SameOriginAdvertisement,
    NextcloudSignedAppPackage,
    NextcloudSignedCompatibleAppPackage,
    AppStoreLinkedExactGitHubTag,
    AppStoreLinkedCompatibleGitHubTag,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SuccessfulReadObservation {
    pub operation_id: Option<String>,
    pub label: Option<String>,
    pub path: String,
    #[serde(default)]
    pub query_parameters: Vec<ObservedQueryParameter>,
    pub status: u16,
    pub content_type: String,
    pub response: Value,
    #[serde(default)]
    pub permission_ids: Vec<String>,
    pub ocs: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ObservedQueryParameter {
    pub name: String,
    pub required: bool,
    pub schema: Value,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum DescriptorValidationError {
    #[error("unsupported dynamic descriptor version: {0}")]
    UnsupportedVersion(String),
    #[error("invalid server origin: {0}")]
    InvalidOrigin(String),
    #[error("invalid endpoint: {0}")]
    InvalidEndpoint(String),
    #[error("cross-origin endpoint is forbidden: {0}")]
    CrossOrigin(String),
    #[error("endpoint is outside approved API prefixes: {0}")]
    UnapprovedEndpoint(String),
    #[error("at least one approved API prefix is required")]
    NoApprovedPrefixes,
    #[error("duplicate {kind} id: {id}")]
    DuplicateId { kind: &'static str, id: String },
    #[error("missing {kind} reference: {id}")]
    MissingReference { kind: &'static str, id: String },
    #[error("mismatched {kind} reference: {id}")]
    MismatchedReference { kind: &'static str, id: String },
    #[error("form {0} points to a read-only action")]
    ReadActionForm(String),
    #[error("mutating action has no OpenAPI or verified-adapter provenance: {0}")]
    UnprovenWrite(String),
}

fn parse_origin(value: &str) -> Result<Url, DescriptorValidationError> {
    let parsed = Url::parse(value)
        .map_err(|_| DescriptorValidationError::InvalidOrigin(value.to_owned()))?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return Err(DescriptorValidationError::InvalidOrigin(value.to_owned()));
    }
    Ok(parsed)
}

fn validate_relative_path(path: &str) -> Result<(), DescriptorValidationError> {
    let lower = path.to_ascii_lowercase();
    if !path.starts_with('/')
        || path.starts_with("//")
        || path.contains(['\\', '?', '#'])
        || lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("%25")
        || path.split('/').any(is_dot_segment)
    {
        return Err(DescriptorValidationError::InvalidEndpoint(path.to_owned()));
    }
    Ok(())
}

fn is_dot_segment(segment: &str) -> bool {
    let decoded_dots = segment.replace("%2e", ".").replace("%2E", ".");
    matches!(decoded_dots.as_str(), "." | "..")
}

fn path_matches_prefix(path: &str, prefix: &str) -> bool {
    let prefix = prefix.trim_end_matches('/');
    path == prefix
        || path
            .strip_prefix(prefix)
            .is_some_and(|rest| rest.starts_with('/'))
}

fn path_placeholders(path: &str) -> BTreeSet<&str> {
    path.split('/')
        .filter_map(|segment| segment.strip_prefix('{')?.strip_suffix('}'))
        .filter(|value| !value.is_empty())
        .collect()
}

fn ensure_unique<'a>(
    ids: impl Iterator<Item = &'a str>,
    kind: &'static str,
) -> Result<(), DescriptorValidationError> {
    let mut seen = BTreeMap::<&str, ()>::new();
    for id in ids {
        if seen.insert(id, ()).is_some() {
            return Err(DescriptorValidationError::DuplicateId {
                kind,
                id: id.to_owned(),
            });
        }
    }
    Ok(())
}

fn require_field(
    resource: &DynamicResource,
    field_id: &str,
) -> Result<(), DescriptorValidationError> {
    if resource.fields.iter().any(|field| field.id == field_id) {
        Ok(())
    } else {
        Err(DescriptorValidationError::MissingReference {
            kind: "field",
            id: format!("{}.{field_id}", resource.id),
        })
    }
}

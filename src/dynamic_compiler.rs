use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value, json};
use thiserror::Error;
use url::Url;

use crate::Confidence;
use crate::{
    ActionIntent, ActionRisk, AuthKind, AuthRequirement, DescriptorValidationError, DynamicAction,
    DynamicAppDescriptor, DynamicDiscoveryInput, DynamicField, DynamicForm, DynamicHttpBinding,
    DynamicLayout, DynamicLink, DynamicLinkTarget, DynamicResource, DynamicWarning, FieldKind,
    FormField, HttpBody, HttpMethod, HttpParameter, LayoutField, LayoutFieldRole, LayoutKind,
    OcsMetadata, OpenApiTrust, ParameterSource, PermissionKind, PermissionSpec, PermissionState,
    Provenance, ProvenanceKind, SuccessfulReadObservation,
};

#[derive(Debug, Error)]
pub enum DynamicCompileError {
    #[error(transparent)]
    Validation(#[from] DescriptorValidationError),
    #[error("advertised OpenAPI document is not OpenAPI 3.x")]
    UnsupportedOpenApi,
    #[error("advertised OpenAPI paths must be an object")]
    InvalidPaths,
    #[error("invalid local OpenAPI reference: {0}")]
    InvalidReference(String),
    #[error("OpenAPI server URL is unsupported: {0}")]
    InvalidServer(String),
    #[error("OpenAPI server entries resolve to conflicting path bases: {0}")]
    ConflictingServerBases(String),
    #[error("read observation is not a successful JSON response: {0}")]
    InvalidReadObservation(String),
}

#[derive(Debug, Default)]
pub struct DynamicDescriptorCompiler;

impl DynamicDescriptorCompiler {
    pub fn compile(
        &self,
        input: &DynamicDiscoveryInput,
    ) -> Result<DynamicAppDescriptor, DynamicCompileError> {
        input.endpoint_policy.validate()?;
        let mut state = CompilerState::new(input);

        if let Some(advertised) = &input.advertised_openapi {
            if advertised.trust == OpenApiTrust::SameOriginAdvertisement {
                input
                    .endpoint_policy
                    .validate_same_origin_url(&advertised.document_url)?;
            }
            state.import_openapi(
                &advertised.document,
                &advertised.document_url,
                advertised.trust,
            )?;
        }
        for observation in &input.successful_reads {
            state.import_successful_read(observation)?;
        }

        let mut descriptor = state.finish();
        if descriptor.resources.is_empty() {
            add_metadata_fallback(&mut descriptor);
        }
        descriptor.validate()?;
        Ok(descriptor)
    }
}

struct CompilerState<'a> {
    input: &'a DynamicDiscoveryInput,
    resources: BTreeMap<String, ResourceBuilder>,
    actions: BTreeMap<String, DynamicAction>,
    layout_seeds: BTreeMap<String, LayoutSeed>,
    forms: BTreeMap<String, DynamicForm>,
    permissions: BTreeMap<String, PermissionSpec>,
    warnings: Vec<DynamicWarning>,
}

impl<'a> CompilerState<'a> {
    fn new(input: &'a DynamicDiscoveryInput) -> Self {
        Self {
            input,
            resources: BTreeMap::new(),
            actions: BTreeMap::new(),
            layout_seeds: BTreeMap::new(),
            forms: BTreeMap::new(),
            permissions: BTreeMap::new(),
            warnings: Vec::new(),
        }
    }

    fn import_openapi(
        &mut self,
        document: &Value,
        document_url: &str,
        trust: OpenApiTrust,
    ) -> Result<(), DynamicCompileError> {
        let sanitized = (trust != OpenApiTrust::SameOriginAdvertisement)
            .then(|| sanitize_external_schema_references(document));
        let (document, ignored_external_references) = sanitized
            .as_ref()
            .map_or((document, 0), |(document, count)| (document, *count));
        let version = document
            .get("openapi")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if !version.starts_with("3.") {
            return Err(DynamicCompileError::UnsupportedOpenApi);
        }
        let paths = document
            .get("paths")
            .and_then(Value::as_object)
            .ok_or(DynamicCompileError::InvalidPaths)?;
        let server_base = openapi_server_base(
            document,
            &self.input.endpoint_policy.server_origin,
            trust != OpenApiTrust::SameOriginAdvertisement,
        )?;
        let source = Provenance {
            kind: match trust {
                OpenApiTrust::SameOriginAdvertisement => ProvenanceKind::AdvertisedOpenApi,
                OpenApiTrust::NextcloudSignedAppPackage => ProvenanceKind::VerifiedAppPackage,
                OpenApiTrust::NextcloudSignedCompatibleAppPackage => {
                    ProvenanceKind::VerifiedAppPackage
                }
                OpenApiTrust::AppStoreLinkedExactGitHubTag => {
                    ProvenanceKind::AppStoreLinkedSourceTag
                }
                OpenApiTrust::AppStoreLinkedCompatibleGitHubTag => {
                    ProvenanceKind::AppStoreLinkedSourceTag
                }
            },
            source: document_url.to_owned(),
            detail: match trust {
                OpenApiTrust::SameOriginAdvertisement => {
                    format!("Imported advertised OpenAPI {version}")
                }
                OpenApiTrust::NextcloudSignedAppPackage => format!(
                    "Imported OpenAPI {version} from a verified Nextcloud App Store package"
                ),
                OpenApiTrust::NextcloudSignedCompatibleAppPackage => format!(
                    "Imported OpenAPI {version} from a verified patch-compatible Nextcloud App Store package"
                ),
                OpenApiTrust::AppStoreLinkedExactGitHubTag => format!(
                    "Imported unsigned OpenAPI {version} from the exact GitHub release tag linked by Nextcloud App Store metadata"
                ),
                OpenApiTrust::AppStoreLinkedCompatibleGitHubTag => format!(
                    "Imported unsigned OpenAPI {version} from a patch-compatible GitHub release tag linked by Nextcloud App Store metadata"
                ),
            },
        };
        if ignored_external_references > 0 {
            self.warnings.push(DynamicWarning {
                code: "opaque-external-schema-reference".to_owned(),
                message: format!(
                    "Ignored {ignored_external_references} external OpenAPI schema references; endpoints remain available without inferred fields."
                ),
            });
        }

        for (openapi_path, path_item_value) in paths {
            let Some(path_item) = path_item_value.as_object() else {
                continue;
            };
            if !openapi_path.starts_with('/') || openapi_path.starts_with("//") {
                return Err(
                    DescriptorValidationError::InvalidEndpoint(openapi_path.clone()).into(),
                );
            }
            let path = combine_paths(&server_base, openapi_path);
            self.input.endpoint_policy.validate_api_path(&path)?;
            let path_parameters = path_item.get("parameters").and_then(Value::as_array);

            for (method_name, operation_value) in path_item {
                let Some(method) = parse_http_method(method_name) else {
                    continue;
                };
                let Some(operation) = operation_value.as_object() else {
                    continue;
                };
                let operation_id = operation
                    .get("operationId")
                    .and_then(Value::as_str)
                    .filter(|value| !value.is_empty());
                if !method.is_read_only() && operation_id.is_none() {
                    self.warnings.push(DynamicWarning {
                        code: "ignored-unnamed-write".to_owned(),
                        message: format!(
                            "Ignored documented {method_name} {path} because it has no operationId"
                        ),
                    });
                    continue;
                }
                let operation_id = operation_id
                    .map(str::to_owned)
                    .unwrap_or_else(|| format!("get-{}", stable_id(&path)));
                let action_id = unique_action_id(&self.actions, &operation_id);
                let resource_id = infer_resource_id(operation, &path, &operation_id);
                let label = operation
                    .get("summary")
                    .and_then(Value::as_str)
                    .map(str::to_owned)
                    .unwrap_or_else(|| humanize(&operation_id));
                let response = openapi_response_schema(operation, document)?;
                let (item_schema, collection) = response
                    .as_ref()
                    .map(|schema| response_item_schema(schema))
                    .unwrap_or((None, false));
                let response_fields = item_schema
                    .map(|schema| fields_from_openapi(schema, document, &source))
                    .transpose()?
                    .unwrap_or_default();
                let resource = self
                    .resources
                    .entry(resource_id.clone())
                    .or_insert_with(|| ResourceBuilder::new(&resource_id));
                resource.collection |= collection;
                resource.confidence = Confidence::High;
                resource
                    .provenance
                    .insert(source_key(&source), source.clone());
                resource.merge_fields(response_fields);

                let (path_parameters, query_parameters) = openapi_parameters(
                    path_parameters,
                    operation.get("parameters").and_then(Value::as_array),
                    document,
                )?;
                let body = openapi_body(operation, document)?;
                let auth = openapi_auth(operation, document);
                let permission_ids = self.register_auth_permissions(&auth, &source);
                let ocs = ocs_metadata(&path, &query_parameters);
                let action = DynamicAction {
                    id: action_id.clone(),
                    label: label.clone(),
                    resource_id: resource_id.clone(),
                    intent: infer_intent(method, &path, &operation_id),
                    risk: risk_for(method),
                    requires_confirmation: !method.is_read_only(),
                    binding: DynamicHttpBinding {
                        method,
                        path: path.clone(),
                        path_parameters,
                        query_parameters,
                        body: body.clone(),
                        auth,
                        ocs,
                    },
                    capability_ids: Vec::new(),
                    permission_ids,
                    confidence: Confidence::High,
                    provenance: vec![source.clone()],
                };
                self.actions.insert(action_id.clone(), action);

                if method.is_read_only() {
                    let kind = if collection {
                        LayoutKind::List
                    } else {
                        LayoutKind::Detail
                    };
                    let layout_id = format!("{resource_id}.{}", layout_suffix(kind));
                    self.layout_seeds
                        .entry(layout_id.clone())
                        .or_insert(LayoutSeed {
                            id: layout_id,
                            title: humanize(&resource_id),
                            resource_id: resource_id.clone(),
                            kind,
                            source_action_id: Some(action_id),
                            confidence: Confidence::High,
                            provenance: vec![source.clone()],
                        });
                } else if let Some(body) = body
                    && let Some(form_fields) = form_fields_from_schema(&body.schema)
                {
                    let form_id = format!("{}.form", action_id);
                    self.forms.insert(
                        form_id.clone(),
                        DynamicForm {
                            id: form_id,
                            title: label,
                            resource_id,
                            action_id,
                            fields: form_fields,
                            confidence: Confidence::High,
                            provenance: vec![source.clone()],
                        },
                    );
                }
            }
        }
        Ok(())
    }

    fn import_successful_read(
        &mut self,
        observation: &SuccessfulReadObservation,
    ) -> Result<(), DynamicCompileError> {
        if !(200..300).contains(&observation.status)
            || !observation
                .content_type
                .to_ascii_lowercase()
                .contains("json")
        {
            return Err(DynamicCompileError::InvalidReadObservation(
                observation.path.clone(),
            ));
        }
        self.input
            .endpoint_policy
            .validate_api_path(&observation.path)?;
        let source = Provenance {
            kind: ProvenanceKind::SuccessfulReadObservation,
            source: observation.path.clone(),
            detail: format!(
                "Inferred only read-only structure from HTTP {} {}",
                observation.status, observation.content_type
            ),
        };
        let payload = unwrap_observed_payload(&observation.response, observation.ocs);
        let collection = payload.is_array();
        let resource_id = infer_observed_resource_id(&observation.path);
        let fields = fields_from_observed_json(payload, &source);
        let resource = self
            .resources
            .entry(resource_id.clone())
            .or_insert_with(|| ResourceBuilder::new(&resource_id));
        resource.collection |= collection;
        resource
            .provenance
            .insert(source_key(&source), source.clone());
        resource.merge_fields(fields);
        if resource.confidence < Confidence::Medium {
            resource.confidence = Confidence::Medium;
        }

        let operation_id = observation
            .operation_id
            .clone()
            .unwrap_or_else(|| format!("observed.get.{}", stable_id(&observation.path)));
        let action_id = unique_action_id(&self.actions, &operation_id);
        let auth = vec![AuthRequirement {
            scheme: "nextcloud-session".to_owned(),
            kind: AuthKind::NextcloudSession,
            scopes: Vec::new(),
        }];
        let mut permission_ids = self.register_auth_permissions(&auth, &source);
        permission_ids.extend(observation.permission_ids.iter().cloned());
        permission_ids.sort();
        permission_ids.dedup();
        for permission_id in &observation.permission_ids {
            self.permissions
                .entry(permission_id.clone())
                .or_insert(PermissionSpec {
                    id: permission_id.clone(),
                    label: humanize(permission_id),
                    kind: PermissionKind::ResourceAcl,
                    state: PermissionState::Unknown,
                    confidence: Confidence::Medium,
                    provenance: source.clone(),
                });
        }
        let query_parameters = observation
            .query_parameters
            .iter()
            .map(|parameter| HttpParameter {
                name: parameter.name.clone(),
                required: parameter.required,
                schema: parameter.schema.clone(),
                source: ParameterSource::UserInput,
            })
            .collect::<Vec<_>>();
        self.actions.insert(
            action_id.clone(),
            DynamicAction {
                id: action_id.clone(),
                label: observation
                    .label
                    .clone()
                    .unwrap_or_else(|| format!("Read {}", humanize(&resource_id))),
                resource_id: resource_id.clone(),
                intent: if collection {
                    ActionIntent::List
                } else {
                    ActionIntent::Read
                },
                risk: ActionRisk::ReadOnly,
                requires_confirmation: false,
                binding: DynamicHttpBinding {
                    method: HttpMethod::Get,
                    path: observation.path.clone(),
                    path_parameters: Vec::new(),
                    query_parameters: query_parameters.clone(),
                    body: None,
                    auth,
                    ocs: observation
                        .ocs
                        .then(|| ocs_metadata(&observation.path, &query_parameters))
                        .flatten(),
                },
                capability_ids: Vec::new(),
                permission_ids,
                confidence: Confidence::Medium,
                provenance: vec![source.clone()],
            },
        );
        let kind = if collection {
            LayoutKind::List
        } else {
            LayoutKind::Detail
        };
        let layout_id = format!("{resource_id}.{}", layout_suffix(kind));
        self.layout_seeds
            .entry(layout_id.clone())
            .or_insert(LayoutSeed {
                id: layout_id,
                title: humanize(&resource_id),
                resource_id,
                kind,
                source_action_id: Some(action_id),
                confidence: Confidence::Medium,
                provenance: vec![source],
            });
        Ok(())
    }

    fn register_auth_permissions(
        &mut self,
        auth: &[AuthRequirement],
        provenance: &Provenance,
    ) -> Vec<String> {
        auth.iter()
            .map(|requirement| {
                let id = format!("auth.{}", stable_id(&requirement.scheme));
                self.permissions
                    .entry(id.clone())
                    .or_insert(PermissionSpec {
                        id: id.clone(),
                        label: format!("{} authentication", humanize(&requirement.scheme)),
                        kind: if requirement.scopes.is_empty() {
                            PermissionKind::AuthenticatedSession
                        } else {
                            PermissionKind::ApiScope
                        },
                        state: PermissionState::Required,
                        confidence: match provenance.kind {
                            ProvenanceKind::AdvertisedOpenApi
                            | ProvenanceKind::VerifiedAdapter
                            | ProvenanceKind::VerifiedAppPackage
                            | ProvenanceKind::AppStoreLinkedSourceTag => Confidence::High,
                            _ => Confidence::Medium,
                        },
                        provenance: provenance.clone(),
                    });
                id
            })
            .collect()
    }

    fn finish(self) -> DynamicAppDescriptor {
        let resources = self
            .resources
            .into_values()
            .map(ResourceBuilder::finish)
            .collect::<Vec<_>>();
        let layouts = self
            .layout_seeds
            .into_values()
            .filter_map(|seed| {
                let resource = resources.iter().find(|item| item.id == seed.resource_id)?;
                Some(seed.finish(resource))
            })
            .collect::<Vec<_>>();
        let links = resources
            .iter()
            .flat_map(|resource| {
                resource
                    .fields
                    .iter()
                    .filter(|field| field.format.as_deref() == Some("uri"))
                    .map(move |field| DynamicLink {
                        id: format!("{}.{}.link", resource.id, field.id),
                        label: field.label.clone(),
                        resource_id: resource.id.clone(),
                        source_field_id: field.id.clone(),
                        target: DynamicLinkTarget::FieldUrl {
                            // Inferred URLs remain display/copy targets until a verified adapter opts in.
                            allow_external: false,
                        },
                        confidence: field.confidence,
                        provenance: field.provenance.clone(),
                    })
            })
            .collect();
        DynamicAppDescriptor {
            descriptor_version: crate::DYNAMIC_APP_DESCRIPTOR_VERSION.to_owned(),
            app: self.input.app.clone(),
            endpoint_policy: self.input.endpoint_policy.clone(),
            capabilities: self.input.capabilities.clone(),
            permissions: self.permissions.into_values().collect(),
            resources,
            layouts,
            links,
            forms: self.forms.into_values().collect(),
            actions: self.actions.into_values().collect(),
            warnings: self.warnings,
        }
    }
}

#[derive(Debug)]
struct ResourceBuilder {
    id: String,
    label: String,
    collection: bool,
    fields: BTreeMap<String, DynamicField>,
    confidence: Confidence,
    provenance: BTreeMap<String, Provenance>,
}

impl ResourceBuilder {
    fn new(id: &str) -> Self {
        Self {
            id: id.to_owned(),
            label: humanize(id),
            collection: false,
            fields: BTreeMap::new(),
            confidence: Confidence::Low,
            provenance: BTreeMap::new(),
        }
    }

    fn merge_fields(&mut self, fields: Vec<DynamicField>) {
        for field in fields {
            self.fields.entry(field.id.clone()).or_insert(field);
        }
    }

    fn finish(self) -> DynamicResource {
        DynamicResource {
            id: self.id,
            label: self.label,
            collection: self.collection,
            fields: self.fields.into_values().collect(),
            capability_ids: Vec::new(),
            permission_ids: Vec::new(),
            confidence: self.confidence,
            provenance: self.provenance.into_values().collect(),
        }
    }
}

struct LayoutSeed {
    id: String,
    title: String,
    resource_id: String,
    kind: LayoutKind,
    source_action_id: Option<String>,
    confidence: Confidence,
    provenance: Vec<Provenance>,
}

impl LayoutSeed {
    fn finish(self, resource: &DynamicResource) -> DynamicLayout {
        DynamicLayout {
            id: self.id,
            title: self.title,
            resource_id: self.resource_id,
            kind: self.kind,
            fields: resource
                .fields
                .iter()
                .enumerate()
                .map(|(index, field)| LayoutField {
                    field_id: field.id.clone(),
                    role: layout_role(field, index),
                    visible: index < if self.kind == LayoutKind::List { 5 } else { 32 },
                })
                .collect(),
            source_action_id: self.source_action_id,
            confidence: self.confidence,
            provenance: self.provenance,
        }
    }
}

fn add_metadata_fallback(descriptor: &mut DynamicAppDescriptor) {
    let provenance = Provenance {
        kind: ProvenanceKind::AppMetadata,
        source: descriptor.app.id.clone(),
        detail: "Installed app identity only".to_owned(),
    };
    descriptor.resources.push(DynamicResource {
        id: "app-metadata".to_owned(),
        label: "App metadata".to_owned(),
        collection: false,
        fields: [("id", "App ID"), ("name", "Name"), ("version", "Version")]
            .into_iter()
            .map(|(id, label)| DynamicField {
                id: id.to_owned(),
                label: label.to_owned(),
                kind: FieldKind::String,
                required: true,
                read_only: true,
                nullable: false,
                multiple: false,
                format: None,
                enum_values: None,
                confidence: Confidence::Verified,
                provenance: vec![provenance.clone()],
            })
            .collect(),
        capability_ids: Vec::new(),
        permission_ids: Vec::new(),
        confidence: Confidence::Low,
        provenance: vec![provenance.clone()],
    });
    descriptor.layouts.push(DynamicLayout {
        id: "app-metadata.detail".to_owned(),
        title: descriptor.app.name.clone(),
        resource_id: "app-metadata".to_owned(),
        kind: LayoutKind::Detail,
        fields: ["id", "name", "version"]
            .into_iter()
            .enumerate()
            .map(|(index, id)| LayoutField {
                field_id: id.to_owned(),
                role: if index == 1 {
                    LayoutFieldRole::Title
                } else {
                    LayoutFieldRole::Metadata
                },
                visible: true,
            })
            .collect(),
        source_action_id: None,
        confidence: Confidence::Low,
        provenance: vec![provenance],
    });
    descriptor.warnings.push(DynamicWarning {
        code: "metadata-only".to_owned(),
        message: "No advertised OpenAPI or approved successful JSON read was available; no endpoint actions were created"
            .to_owned(),
    });
}

#[derive(Debug)]
struct NormalizedOpenApiServer {
    path_base: String,
    requires_trusted_rebase: bool,
    original: String,
}

fn sanitize_external_schema_references(document: &Value) -> (Value, usize) {
    fn sanitize(value: &Value, ignored: &mut usize) -> Value {
        match value {
            Value::Array(values) => Value::Array(
                values
                    .iter()
                    .map(|value| sanitize(value, ignored))
                    .collect(),
            ),
            Value::Object(object) => {
                let is_external = object
                    .get("$ref")
                    .and_then(Value::as_str)
                    .is_some_and(|reference| !reference.starts_with("#/"));
                if is_external {
                    *ignored += 1;
                }
                Value::Object(
                    object
                        .iter()
                        .filter(|(key, _)| !(is_external && key.as_str() == "$ref"))
                        .map(|(key, value)| (key.clone(), sanitize(value, ignored)))
                        .collect(),
                )
            }
            _ => value.clone(),
        }
    }

    let mut ignored = 0;
    (sanitize(document, &mut ignored), ignored)
}

fn openapi_server_base(
    document: &Value,
    origin: &str,
    allow_trusted_rebase: bool,
) -> Result<String, DynamicCompileError> {
    let Some(servers) = document.get("servers").and_then(Value::as_array) else {
        return Ok(String::new());
    };
    let urls = servers
        .iter()
        .filter_map(|server| server.get("url").and_then(Value::as_str))
        .collect::<BTreeSet<_>>();
    let Some(first) = urls.iter().next() else {
        return Ok(String::new());
    };
    if urls.len() == 1 {
        if first.contains('{') || first.contains(['?', '#']) {
            return Err(DynamicCompileError::InvalidServer((*first).to_owned()));
        }
        let normalized = normalize_openapi_server(first, origin)?;
        if normalized.requires_trusted_rebase {
            return Err(DescriptorValidationError::CrossOrigin((*first).to_owned()).into());
        }
        return Ok(normalized.path_base);
    }

    let normalized = urls
        .iter()
        .map(|value| normalize_openapi_server(value, origin))
        .collect::<Result<Vec<_>, _>>()?;
    let path_bases = normalized
        .iter()
        .map(|server| server.path_base.as_str())
        .collect::<BTreeSet<_>>();
    if path_bases.len() != 1 {
        return Err(DynamicCompileError::ConflictingServerBases(
            path_bases.into_iter().collect::<Vec<_>>().join(", "),
        ));
    }
    let rebased = normalized
        .iter()
        .filter(|server| server.requires_trusted_rebase)
        .collect::<Vec<_>>();
    if !rebased.is_empty() && !allow_trusted_rebase {
        return Err(DynamicCompileError::InvalidServer(
            rebased
                .into_iter()
                .map(|server| server.original.as_str())
                .collect::<Vec<_>>()
                .join(", "),
        ));
    }
    Ok(path_bases.into_iter().next().unwrap_or_default().to_owned())
}

fn normalize_openapi_server(
    value: &str,
    origin: &str,
) -> Result<NormalizedOpenApiServer, DynamicCompileError> {
    if value.is_empty() || value.contains(['?', '#', '\\']) {
        return Err(DynamicCompileError::InvalidServer(value.to_owned()));
    }
    if value.starts_with('/') && !value.starts_with("//") {
        if value.contains('{') {
            return Err(DynamicCompileError::InvalidServer(value.to_owned()));
        }
        return Ok(NormalizedOpenApiServer {
            path_base: value.trim_end_matches('/').to_owned(),
            requires_trusted_rebase: false,
            original: value.to_owned(),
        });
    }

    let Some((scheme, remainder)) = value.split_once("://") else {
        return Err(DynamicCompileError::InvalidServer(value.to_owned()));
    };
    if scheme.is_empty() {
        return Err(DynamicCompileError::InvalidServer(value.to_owned()));
    }
    let (authority, path) = remainder
        .split_once('/')
        .map_or((remainder, ""), |(authority, path)| (authority, path));
    if authority.is_empty()
        || authority.contains('@')
        || authority.chars().any(char::is_whitespace)
        || path.contains('{')
    {
        return Err(DynamicCompileError::InvalidServer(value.to_owned()));
    }
    let path_base = if path.is_empty() {
        String::new()
    } else {
        format!("/{}", path.trim_end_matches('/'))
    };
    let requires_trusted_rebase = if scheme.contains('{') || authority.contains('{') {
        true
    } else {
        let declared = Url::parse(&format!("{scheme}://{authority}"))
            .map_err(|_| DynamicCompileError::InvalidServer(value.to_owned()))?;
        let current =
            Url::parse(origin).map_err(|_| DynamicCompileError::InvalidServer(value.to_owned()))?;
        declared.origin() != current.origin()
    };
    Ok(NormalizedOpenApiServer {
        path_base,
        requires_trusted_rebase,
        original: value.to_owned(),
    })
}

fn combine_paths(base: &str, path: &str) -> String {
    format!(
        "{}/{}",
        base.trim_end_matches('/'),
        path.trim_start_matches('/')
    )
}

fn parse_http_method(value: &str) -> Option<HttpMethod> {
    match value.to_ascii_lowercase().as_str() {
        "get" => Some(HttpMethod::Get),
        "post" => Some(HttpMethod::Post),
        "put" => Some(HttpMethod::Put),
        "patch" => Some(HttpMethod::Patch),
        "delete" => Some(HttpMethod::Delete),
        _ => None,
    }
}

fn openapi_response_schema(
    operation: &Map<String, Value>,
    document: &Value,
) -> Result<Option<Value>, DynamicCompileError> {
    let response = operation
        .get("responses")
        .and_then(Value::as_object)
        .and_then(|responses| {
            responses
                .iter()
                .find(|(status, _)| status.starts_with('2'))
                .map(|(_, response)| response)
        });
    let schema = response
        .and_then(|response| response.get("content"))
        .and_then(Value::as_object)
        .and_then(|content| {
            content
                .get("application/json")
                .or_else(|| content.get("application/problem+json"))
        })
        .and_then(|media| media.get("schema"));
    schema
        .map(|value| resolve_local(value, document, 0))
        .transpose()
}

fn response_item_schema(schema: &Value) -> (Option<&Value>, bool) {
    if schema.get("type").and_then(Value::as_str) == Some("array") {
        return (schema.get("items"), true);
    }
    if let Some(properties) = schema.get("properties").and_then(Value::as_object) {
        for key in ["ocs", "data"] {
            if let Some(value) = properties.get(key) {
                let (item, collection) = response_item_schema(value);
                if item.is_some() {
                    return (item, collection);
                }
            }
        }
    }
    let is_object = schema.get("type").and_then(Value::as_str) == Some("object")
        || schema.get("properties").is_some();
    (is_object.then_some(schema), false)
}

fn fields_from_openapi(
    schema: &Value,
    document: &Value,
    provenance: &Provenance,
) -> Result<Vec<DynamicField>, DynamicCompileError> {
    let schema = resolve_local(schema, document, 0)?;
    let required = schema
        .get("required")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .collect::<BTreeSet<_>>()
        })
        .unwrap_or_default();
    let Some(properties) = schema.get("properties").and_then(Value::as_object) else {
        return Ok(Vec::new());
    };
    Ok(properties
        .iter()
        .map(|(id, value)| DynamicField {
            id: id.clone(),
            label: value
                .get("title")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or_else(|| humanize(id)),
            kind: infer_openapi_field_kind(id, value),
            required: required.contains(id.as_str()),
            read_only: value
                .get("readOnly")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            nullable: value
                .get("nullable")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            multiple: value.get("type").and_then(Value::as_str) == Some("array"),
            format: value
                .get("format")
                .and_then(Value::as_str)
                .map(str::to_owned),
            enum_values: string_enum(value),
            confidence: Confidence::High,
            provenance: vec![provenance.clone()],
        })
        .collect())
}

fn openapi_parameters(
    path_parameters: Option<&Vec<Value>>,
    operation_parameters: Option<&Vec<Value>>,
    document: &Value,
) -> Result<(Vec<HttpParameter>, Vec<HttpParameter>), DynamicCompileError> {
    let mut path = BTreeMap::<String, HttpParameter>::new();
    let mut query = BTreeMap::<String, HttpParameter>::new();
    for value in path_parameters
        .into_iter()
        .flatten()
        .chain(operation_parameters.into_iter().flatten())
    {
        let value = resolve_local(value, document, 0)?;
        let Some(name) = value.get("name").and_then(Value::as_str) else {
            continue;
        };
        let location = value.get("in").and_then(Value::as_str).unwrap_or_default();
        let parameter = HttpParameter {
            name: name.to_owned(),
            required: value
                .get("required")
                .and_then(Value::as_bool)
                .unwrap_or(location == "path"),
            schema: value.get("schema").cloned().unwrap_or_else(|| json!({})),
            source: if location == "path" {
                ParameterSource::ResourceField
            } else {
                ParameterSource::UserInput
            },
        };
        match location {
            "path" => {
                path.insert(name.to_owned(), parameter);
            }
            "query" => {
                query.insert(name.to_owned(), parameter);
            }
            _ => {}
        }
    }
    Ok((path.into_values().collect(), query.into_values().collect()))
}

fn openapi_body(
    operation: &Map<String, Value>,
    document: &Value,
) -> Result<Option<HttpBody>, DynamicCompileError> {
    let Some(body) = operation.get("requestBody") else {
        return Ok(None);
    };
    let body = resolve_local(body, document, 0)?;
    let Some(content) = body.get("content").and_then(Value::as_object) else {
        return Ok(None);
    };
    let preferred = [
        "application/json",
        "application/x-www-form-urlencoded",
        "multipart/form-data",
    ];
    let selected = preferred
        .iter()
        .find_map(|content_type| {
            content
                .get(*content_type)
                .map(|media| (*content_type, media))
        })
        .or_else(|| {
            content
                .iter()
                .next()
                .map(|(key, value)| (key.as_str(), value))
        });
    let Some((content_type, media)) = selected else {
        return Ok(None);
    };
    let Some(schema) = media.get("schema") else {
        return Ok(None);
    };
    Ok(Some(HttpBody {
        content_type: content_type.to_owned(),
        required: body
            .get("required")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        schema: resolve_local(schema, document, 0)?,
    }))
}

fn openapi_auth(operation: &Map<String, Value>, document: &Value) -> Vec<AuthRequirement> {
    let security = operation
        .get("security")
        .or_else(|| document.get("security"))
        .and_then(Value::as_array);
    let schemes = document
        .pointer("/components/securitySchemes")
        .and_then(Value::as_object);
    let mut auth = BTreeMap::<String, AuthRequirement>::new();
    for requirement in security.into_iter().flatten().filter_map(Value::as_object) {
        for (name, scopes) in requirement {
            let definition = schemes.and_then(|items| items.get(name));
            let kind = auth_kind(definition);
            auth.insert(
                name.clone(),
                AuthRequirement {
                    scheme: name.clone(),
                    kind,
                    scopes: scopes
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(Value::as_str)
                        .map(str::to_owned)
                        .collect(),
                },
            );
        }
    }
    if auth.is_empty() {
        auth.insert(
            "nextcloud-session".to_owned(),
            AuthRequirement {
                scheme: "nextcloud-session".to_owned(),
                kind: AuthKind::NextcloudSession,
                scopes: Vec::new(),
            },
        );
    }
    auth.into_values().collect()
}

fn auth_kind(definition: Option<&Value>) -> AuthKind {
    let definition_type = definition
        .and_then(|value| value.get("type"))
        .and_then(Value::as_str)
        .unwrap_or_default();
    let scheme = definition
        .and_then(|value| value.get("scheme"))
        .and_then(Value::as_str)
        .unwrap_or_default();
    match (definition_type, scheme) {
        ("http", "basic") => AuthKind::Basic,
        ("http", "bearer") => AuthKind::Bearer,
        ("apiKey", _)
            if definition
                .and_then(|value| value.get("in"))
                .and_then(Value::as_str)
                == Some("cookie") =>
        {
            AuthKind::Cookie
        }
        ("apiKey", _) => AuthKind::ApiKey,
        ("oauth2", _) => AuthKind::OAuth2,
        ("openIdConnect", _) => AuthKind::OpenIdConnect,
        _ => AuthKind::NextcloudSession,
    }
}

fn ocs_metadata(path: &str, query: &[HttpParameter]) -> Option<OcsMetadata> {
    (path.contains("/ocs/") || path.contains("/ocs/v1.php/") || path.contains("/ocs/v2.php/")).then(
        || OcsMetadata {
            api_request_header: true,
            response_data_pointer: "/ocs/data".to_owned(),
            response_meta_pointer: "/ocs/meta".to_owned(),
            format_query_parameter: query
                .iter()
                .find(|parameter| parameter.name == "format")
                .map(|parameter| parameter.name.clone()),
        },
    )
}

fn form_fields_from_schema(schema: &Value) -> Option<Vec<FormField>> {
    let required = schema
        .get("required")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .collect::<BTreeSet<_>>()
        })
        .unwrap_or_default();
    let properties = schema.get("properties")?.as_object()?;
    Some(
        properties
            .iter()
            .filter(|(_, value)| {
                !value
                    .get("readOnly")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
            })
            .map(|(id, value)| FormField {
                field_id: id.clone(),
                label: value
                    .get("title")
                    .and_then(Value::as_str)
                    .map(str::to_owned)
                    .unwrap_or_else(|| humanize(id)),
                kind: infer_openapi_field_kind(id, value),
                required: required.contains(id.as_str()),
                format: value
                    .get("format")
                    .and_then(Value::as_str)
                    .map(str::to_owned),
                enum_values: string_enum(value),
            })
            .collect(),
    )
}

fn unwrap_observed_payload(value: &Value, ocs: bool) -> &Value {
    if ocs && let Some(data) = value.pointer("/ocs/data") {
        return data;
    }
    value
        .as_object()
        .filter(|object| object.len() == 1)
        .and_then(|object| object.get("data"))
        .unwrap_or(value)
}

fn fields_from_observed_json(value: &Value, provenance: &Provenance) -> Vec<DynamicField> {
    let samples = match value {
        Value::Array(items) => items
            .iter()
            .filter_map(Value::as_object)
            .take(64)
            .collect::<Vec<_>>(),
        Value::Object(object) => vec![object],
        _ => Vec::new(),
    };
    if samples.is_empty() {
        return if value.is_array() {
            Vec::new()
        } else {
            vec![observed_field("value", &[value], 1, provenance)]
        };
    }
    let field_ids = samples
        .iter()
        .flat_map(|sample| sample.keys())
        .take(512)
        .cloned()
        .collect::<BTreeSet<_>>();
    field_ids
        .into_iter()
        .take(128)
        .map(|id| {
            let values = samples
                .iter()
                .filter_map(|sample| sample.get(&id))
                .collect::<Vec<_>>();
            observed_field(&id, &values, samples.len(), provenance)
        })
        .collect()
}

fn observed_field(
    id: &str,
    values: &[&Value],
    sample_count: usize,
    provenance: &Provenance,
) -> DynamicField {
    let present_non_null = values.iter().filter(|value| !value.is_null()).count();
    let multiple = values.iter().any(|value| value.is_array());
    let scalar_values = values
        .iter()
        .flat_map(|value| {
            value
                .as_array()
                .map(Vec::as_slice)
                .unwrap_or_else(|| std::slice::from_ref(*value))
        })
        .collect::<Vec<_>>();
    let kind = infer_observed_field_kind(id, &scalar_values);
    let format = infer_observed_format(id, &scalar_values);
    DynamicField {
        id: id.to_owned(),
        label: humanize(id),
        kind,
        required: sample_count > 0 && present_non_null == sample_count,
        read_only: true,
        nullable: present_non_null < values.len() || values.len() < sample_count,
        multiple,
        format,
        enum_values: None,
        confidence: Confidence::Medium,
        provenance: vec![provenance.clone()],
    }
}

fn infer_observed_field_kind(id: &str, values: &[&Value]) -> FieldKind {
    let non_null = values
        .iter()
        .filter(|value| !value.is_null())
        .collect::<Vec<_>>();
    if non_null.is_empty() {
        return FieldKind::Unknown;
    }
    if non_null.iter().all(|value| value.is_boolean()) {
        FieldKind::Boolean
    } else if non_null
        .iter()
        .all(|value| value.as_i64().is_some() || value.as_u64().is_some())
    {
        FieldKind::Integer
    } else if non_null.iter().all(|value| value.is_number()) {
        FieldKind::Decimal
    } else if non_null.iter().all(|value| value.is_string()) {
        semantic_string_kind(id)
    } else if non_null
        .iter()
        .all(|value| value.is_object() || value.is_array())
    {
        FieldKind::Object
    } else {
        FieldKind::Unknown
    }
}

fn infer_observed_format(id: &str, values: &[&Value]) -> Option<String> {
    let strings = values
        .iter()
        .filter_map(|value| value.as_str())
        .collect::<Vec<_>>();
    if !strings.is_empty()
        && strings
            .iter()
            .all(|value| value.starts_with("https://") || value.starts_with("http://"))
    {
        Some("uri".to_owned())
    } else if id.to_ascii_lowercase().contains("date")
        && strings.iter().all(|value| value.len() >= 10)
    {
        Some("date-time".to_owned())
    } else {
        None
    }
}

fn resolve_local(
    value: &Value,
    document: &Value,
    depth: usize,
) -> Result<Value, DynamicCompileError> {
    if depth > 24 {
        return Ok(value.clone());
    }
    if let Some(reference) = value.get("$ref").and_then(Value::as_str) {
        let pointer = reference
            .strip_prefix('#')
            .ok_or_else(|| DynamicCompileError::InvalidReference(reference.to_owned()))?;
        let target = document
            .pointer(pointer)
            .ok_or_else(|| DynamicCompileError::InvalidReference(reference.to_owned()))?;
        return resolve_local(target, document, depth + 1);
    }
    match value {
        Value::Array(values) => values
            .iter()
            .map(|value| resolve_local(value, document, depth + 1))
            .collect::<Result<Vec<_>, _>>()
            .map(Value::Array),
        Value::Object(object) => object
            .iter()
            .map(|(key, value)| {
                resolve_local(value, document, depth + 1).map(|value| (key.clone(), value))
            })
            .collect::<Result<Map<_, _>, _>>()
            .map(Value::Object),
        _ => Ok(value.clone()),
    }
}

fn infer_resource_id(operation: &Map<String, Value>, path: &str, operation_id: &str) -> String {
    operation
        .get("tags")
        .and_then(Value::as_array)
        .and_then(|tags| tags.first())
        .and_then(Value::as_str)
        .map(stable_id)
        .filter(|value| !value.is_empty())
        .or_else(|| {
            operation_id
                .split(['.', '_', '-'])
                .find(|part| !matches!(*part, "get" | "list" | "create" | "update" | "delete"))
                .map(stable_id)
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_else(|| infer_observed_resource_id(path))
}

fn infer_observed_resource_id(path: &str) -> String {
    path.split('/')
        .rev()
        .find(|segment| {
            !segment.is_empty()
                && !segment.starts_with('{')
                && !segment.strip_prefix('v').is_some_and(|suffix| {
                    suffix.chars().all(|character| character.is_ascii_digit())
                })
        })
        .map(stable_id)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "resource".to_owned())
}

fn infer_intent(method: HttpMethod, path: &str, operation_id: &str) -> ActionIntent {
    match method {
        HttpMethod::Get if path.contains('{') => ActionIntent::Read,
        HttpMethod::Get if operation_id.to_ascii_lowercase().contains("list") => ActionIntent::List,
        HttpMethod::Get => ActionIntent::Read,
        HttpMethod::Post => ActionIntent::Create,
        HttpMethod::Put | HttpMethod::Patch => ActionIntent::Update,
        HttpMethod::Delete => ActionIntent::Delete,
    }
}

fn risk_for(method: HttpMethod) -> ActionRisk {
    match method {
        HttpMethod::Get => ActionRisk::ReadOnly,
        HttpMethod::Delete => ActionRisk::Destructive,
        HttpMethod::Post | HttpMethod::Put | HttpMethod::Patch => ActionRisk::Mutating,
    }
}

fn infer_openapi_field_kind(id: &str, value: &Value) -> FieldKind {
    if value.get("enum").is_some() {
        return FieldKind::Enumeration;
    }
    let value = if value.get("type").and_then(Value::as_str) == Some("array") {
        value.get("items").unwrap_or(value)
    } else {
        value
    };
    let value_type = value
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let format = value
        .get("format")
        .and_then(Value::as_str)
        .unwrap_or_default();
    match (value_type, format) {
        ("string", "date") => FieldKind::Date,
        ("string", "date-time") => FieldKind::DateTime,
        ("string", _) => semantic_string_kind(id),
        ("integer", _) => FieldKind::Integer,
        ("number", _) => FieldKind::Decimal,
        ("boolean", _) => FieldKind::Boolean,
        ("object", _) | ("array", _) => FieldKind::Object,
        _ => FieldKind::Unknown,
    }
}

fn semantic_string_kind(id: &str) -> FieldKind {
    let id = id.to_ascii_lowercase();
    if id.contains("currency") {
        FieldKind::Currency
    } else if id.contains("image") || id.contains("preview") || id.contains("thumbnail") {
        FieldKind::Image
    } else if id.contains("file") || id.contains("mime") {
        FieldKind::File
    } else if id == "user" || id.ends_with("userid") || id.ends_with("user_id") {
        FieldKind::UserReference
    } else if id.contains("description") || id.contains("message") || id.contains("content") {
        FieldKind::LongText
    } else {
        FieldKind::String
    }
}

fn string_enum(value: &Value) -> Option<Vec<String>> {
    value.get("enum").and_then(Value::as_array).map(|items| {
        items
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_owned)
            .collect()
    })
}

fn layout_role(field: &DynamicField, index: usize) -> LayoutFieldRole {
    let id = field.id.to_ascii_lowercase();
    if matches!(field.kind, FieldKind::Image) {
        LayoutFieldRole::Image
    } else if matches!(id.as_str(), "id" | "uuid" | "token") {
        LayoutFieldRole::Identity
    } else if matches!(id.as_str(), "title" | "name" | "displayname" | "subject") || index == 0 {
        LayoutFieldRole::Title
    } else if index == 1 {
        LayoutFieldRole::Subtitle
    } else if matches!(field.kind, FieldKind::LongText) {
        LayoutFieldRole::Body
    } else {
        LayoutFieldRole::Metadata
    }
}

fn layout_suffix(kind: LayoutKind) -> &'static str {
    match kind {
        LayoutKind::List => "list",
        LayoutKind::Detail => "detail",
        LayoutKind::Grid => "grid",
    }
}

fn unique_action_id(actions: &BTreeMap<String, DynamicAction>, operation_id: &str) -> String {
    let base = stable_id(operation_id);
    if !actions.contains_key(&base) {
        return base;
    }
    (2..)
        .map(|suffix| format!("{base}-{suffix}"))
        .find(|candidate| !actions.contains_key(candidate))
        .expect("unbounded action id sequence")
}

fn stable_id(value: &str) -> String {
    let mut result = String::new();
    let mut separator = false;
    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            result.push(character.to_ascii_lowercase());
            separator = false;
        } else if !separator && !result.is_empty() {
            result.push('-');
            separator = true;
        }
    }
    result.trim_matches('-').to_owned()
}

fn humanize(value: &str) -> String {
    value
        .replace(['-', '_', '.'], " ")
        .split_whitespace()
        .map(|word| {
            let mut characters = word.chars();
            characters
                .next()
                .map(|first| first.to_uppercase().collect::<String>() + characters.as_str())
                .unwrap_or_default()
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn source_key(provenance: &Provenance) -> String {
    format!("{:?}:{}", provenance.kind, provenance.source)
}

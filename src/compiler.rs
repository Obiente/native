use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value};
use thiserror::Error;

use crate::{
    ActionIntent, ActionRisk, ActionSpec, ApiBinding, CompilerWarning, Confidence,
    DiscoverySnapshot, Evidence, EvidenceSource, FieldKind, FieldSpec, HttpMethod, NativeAppSchema,
    NativeComponent, ResourceSpec, ViewSpec,
};

pub trait NativeSchemaCompiler {
    fn compile(&self, snapshot: &DiscoverySnapshot) -> Result<NativeAppSchema, CompileError>;
}

#[derive(Debug, Error)]
pub enum CompileError {
    #[error("the discovery snapshot contains an invalid OpenAPI paths value")]
    MissingPaths,
    #[error("the OpenAPI document contains an invalid local reference: {0}")]
    InvalidReference(String),
}

#[derive(Debug, Default)]
pub struct OpenApiCompiler;

impl NativeSchemaCompiler for OpenApiCompiler {
    fn compile(&self, snapshot: &DiscoverySnapshot) -> Result<NativeAppSchema, CompileError> {
        let Some(paths_value) = snapshot.openapi.get("paths") else {
            return Ok(metadata_fallback_schema(snapshot));
        };
        let paths = paths_value.as_object().ok_or(CompileError::MissingPaths)?;
        if paths.is_empty() {
            return Ok(metadata_fallback_schema(snapshot));
        }

        let mut schema = NativeAppSchema::empty(snapshot.app.clone());
        let mut resources = BTreeMap::<String, ResourceBuilder>::new();
        let mut views = BTreeMap::<String, ViewSpec>::new();
        let mut actions = Vec::<ActionSpec>::new();

        for (path, path_item) in paths {
            let Some(path_item) = path_item.as_object() else {
                continue;
            };

            for (method_name, operation) in path_item {
                let Some(method) = parse_method(method_name) else {
                    continue;
                };
                let Some(operation) = operation.as_object() else {
                    continue;
                };

                let operation_id = operation
                    .get("operationId")
                    .and_then(Value::as_str)
                    .unwrap_or(path.as_str())
                    .to_owned();
                let resource_id = infer_resource_id(operation, path, &operation_id);
                let resource_name = humanize(&resource_id);
                let response_schema = response_schema(operation, &snapshot.openapi)?;
                let item_schema = collection_item_schema(response_schema.as_ref());
                let request_schema = request_schema(operation, &snapshot.openapi)?;

                let builder = resources
                    .entry(resource_id.clone())
                    .or_insert_with(|| ResourceBuilder::new(&resource_id, &resource_name));

                if let Some(item_schema) = item_schema {
                    builder.merge_fields(fields_from_schema(item_schema, &snapshot.openapi)?);
                    builder.confidence = Confidence::High;
                    builder.evidence.insert(format!(
                        "OpenAPI response schema for {method_name_upper} {path}",
                        method_name_upper = method_name.to_ascii_uppercase()
                    ));
                }

                let intent = infer_intent(method, path, operation);
                let risk = risk_for(method);
                let action_id = stable_id(&operation_id);
                let action = ActionSpec {
                    id: action_id.clone(),
                    label: operation
                        .get("summary")
                        .and_then(Value::as_str)
                        .map(str::to_owned)
                        .unwrap_or_else(|| humanize(&operation_id)),
                    resource_id: resource_id.clone(),
                    binding: ApiBinding {
                        method,
                        path: path.clone(),
                        operation_id: operation_id.clone(),
                    },
                    intent,
                    risk,
                    requires_confirmation: !method.is_read_only(),
                    confidence: Confidence::High,
                    input_schema: request_schema,
                    evidence: vec![Evidence {
                        source: EvidenceSource::OpenApi,
                        detail: format!("Discovered operation {operation_id}"),
                    }],
                };

                if method == HttpMethod::Get {
                    let is_collection = response_schema
                        .as_ref()
                        .is_some_and(|value| schema_contains_array(value, &snapshot.openapi));
                    let component = if is_collection {
                        infer_collection_component(&resource_id, builder)
                    } else {
                        NativeComponent::Detail
                    };
                    let view_id = format!("{resource_id}.{}", component_suffix(&component));
                    views.entry(view_id.clone()).or_insert_with(|| ViewSpec {
                        id: view_id,
                        title: resource_name.clone(),
                        resource_id: resource_id.clone(),
                        component,
                        source_action_id: action_id.clone(),
                        confidence: Confidence::Medium,
                        evidence: vec![Evidence {
                            source: EvidenceSource::LocalInference,
                            detail: format!(
                                "Selected a native component from resource semantics for {resource_id}"
                            ),
                        }],
                    });
                }

                actions.push(action);
            }
        }

        schema.resources = resources
            .into_values()
            .map(ResourceBuilder::finish)
            .collect();
        schema.views = views.into_values().collect();
        schema.actions = actions;

        for resource in &schema.resources {
            let has_write = schema.actions.iter().any(|action| {
                action.resource_id == resource.id && !action.binding.method.is_read_only()
            });
            if has_write {
                schema.views.push(ViewSpec {
                    id: format!("{}.form", resource.id),
                    title: format!("Edit {}", resource.name),
                    resource_id: resource.id.clone(),
                    component: NativeComponent::Form,
                    source_action_id: schema
                        .actions
                        .iter()
                        .find(|action| {
                            action.resource_id == resource.id
                                && !action.binding.method.is_read_only()
                        })
                        .map(|action| action.id.clone())
                        .unwrap_or_default(),
                    confidence: Confidence::Medium,
                    evidence: vec![Evidence {
                        source: EvidenceSource::LocalInference,
                        detail: "A discovered mutating operation requires a native form".to_owned(),
                    }],
                });
            }
        }

        schema.confidence = if schema.resources.is_empty() {
            schema.warnings.push(CompilerWarning {
                code: "no-resources".to_owned(),
                message: "No typed resources could be inferred from the OpenAPI document"
                    .to_owned(),
            });
            Confidence::Low
        } else {
            Confidence::High
        };

        Ok(schema)
    }
}

/// Produces an honest, read-only native screen when discovery has app metadata but no typed API.
/// No action binding is emitted because there is no advertised endpoint to bind safely.
fn metadata_fallback_schema(snapshot: &DiscoverySnapshot) -> NativeAppSchema {
    let mut schema = NativeAppSchema::empty(snapshot.app.clone());
    let resource_id = "app-metadata".to_owned();
    let route_available = snapshot
        .navigation
        .iter()
        .any(|entry| entry.id == snapshot.app.id && !entry.route.is_empty());
    let fields = [
        ("id", "App ID", true),
        ("name", "Name", true),
        ("version", "Version", true),
        ("route", "Advertised route", route_available),
        ("family", "Native family", true),
        ("status", "Native mode", true),
    ]
    .into_iter()
    .filter(|(_, _, include)| *include)
    .map(|(id, label, _)| FieldSpec {
        id: id.to_owned(),
        label: label.to_owned(),
        kind: FieldKind::String,
        required: false,
        read_only: true,
        format: None,
        enum_values: None,
    })
    .collect();

    schema.resources.push(ResourceSpec {
        id: resource_id.clone(),
        name: "App metadata".to_owned(),
        confidence: Confidence::Low,
        fields,
        evidence: vec![Evidence {
            source: EvidenceSource::AppMetadata,
            detail: "Installed app identity and navigation metadata".to_owned(),
        }],
    });
    schema.views.push(ViewSpec {
        id: "app-metadata.detail".to_owned(),
        title: snapshot.app.name.clone(),
        resource_id,
        component: NativeComponent::Detail,
        source_action_id: String::new(),
        confidence: Confidence::Low,
        evidence: vec![Evidence {
            source: EvidenceSource::LocalInference,
            detail: "No typed API was advertised, so only read-only app metadata is rendered"
                .to_owned(),
        }],
    });
    schema.warnings.push(CompilerWarning {
        code: "metadata-only".to_owned(),
        message: "No OpenAPI operations were advertised; native writes and inferred endpoint access are disabled"
            .to_owned(),
    });
    schema
}

#[derive(Debug)]
struct ResourceBuilder {
    id: String,
    name: String,
    confidence: Confidence,
    fields: BTreeMap<String, FieldSpec>,
    evidence: BTreeSet<String>,
}

impl ResourceBuilder {
    fn new(id: &str, name: &str) -> Self {
        Self {
            id: id.to_owned(),
            name: name.to_owned(),
            confidence: Confidence::Medium,
            fields: BTreeMap::new(),
            evidence: BTreeSet::new(),
        }
    }

    fn merge_fields(&mut self, fields: Vec<FieldSpec>) {
        for field in fields {
            self.fields.entry(field.id.clone()).or_insert(field);
        }
    }

    fn finish(self) -> ResourceSpec {
        ResourceSpec {
            id: self.id,
            name: self.name,
            confidence: self.confidence,
            fields: self.fields.into_values().collect(),
            evidence: self
                .evidence
                .into_iter()
                .map(|detail| Evidence {
                    source: EvidenceSource::OpenApi,
                    detail,
                })
                .collect(),
        }
    }
}

fn parse_method(value: &str) -> Option<HttpMethod> {
    match value {
        "get" => Some(HttpMethod::Get),
        "post" => Some(HttpMethod::Post),
        "put" => Some(HttpMethod::Put),
        "patch" => Some(HttpMethod::Patch),
        "delete" => Some(HttpMethod::Delete),
        _ => None,
    }
}

fn infer_resource_id(operation: &Map<String, Value>, path: &str, operation_id: &str) -> String {
    if let Some(tag) = operation
        .get("tags")
        .and_then(Value::as_array)
        .and_then(|tags| tags.first())
        .and_then(Value::as_str)
    {
        return stable_id(tag);
    }

    let operation_prefix = operation_id
        .split(['.', '_', '-'])
        .find(|part| !matches!(*part, "get" | "list" | "create" | "update" | "delete"));
    if let Some(prefix) = operation_prefix
        && !prefix.is_empty()
    {
        return stable_id(prefix);
    }

    path.split('/')
        .rev()
        .find(|segment| !segment.is_empty() && !segment.starts_with('{') && !is_version(segment))
        .map(stable_id)
        .unwrap_or_else(|| "resource".to_owned())
}

fn infer_intent(method: HttpMethod, path: &str, operation: &Map<String, Value>) -> ActionIntent {
    match method {
        HttpMethod::Get if path.contains('{') => ActionIntent::Read,
        HttpMethod::Get => {
            let operation_id = operation
                .get("operationId")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_ascii_lowercase();
            if operation_id.contains("list") || operation_id.contains("get") {
                ActionIntent::List
            } else {
                ActionIntent::Read
            }
        }
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

fn response_schema(
    operation: &Map<String, Value>,
    document: &Value,
) -> Result<Option<Value>, CompileError> {
    let responses = operation.get("responses").and_then(Value::as_object);
    let response = responses.and_then(|responses| {
        responses
            .get("200")
            .or_else(|| responses.get("201"))
            .or_else(|| responses.values().next())
    });
    let schema = response
        .and_then(|response| response.get("content"))
        .and_then(Value::as_object)
        .and_then(|content| {
            content
                .get("application/json")
                .or_else(|| content.values().next())
        })
        .and_then(|media| media.get("schema"));

    schema
        .map(|schema| resolve_schema(schema, document, 0))
        .transpose()
}

fn request_schema(
    operation: &Map<String, Value>,
    document: &Value,
) -> Result<Option<Value>, CompileError> {
    let schema = operation
        .get("requestBody")
        .and_then(|body| body.get("content"))
        .and_then(Value::as_object)
        .and_then(|content| {
            content
                .get("application/json")
                .or_else(|| content.get("application/x-www-form-urlencoded"))
                .or_else(|| content.values().next())
        })
        .and_then(|media| media.get("schema"));

    schema
        .map(|schema| resolve_schema(schema, document, 0))
        .transpose()
}

fn resolve_schema(value: &Value, document: &Value, depth: usize) -> Result<Value, CompileError> {
    if depth > 24 {
        return Ok(value.clone());
    }
    if let Some(reference) = value.get("$ref").and_then(Value::as_str) {
        let pointer = reference
            .strip_prefix('#')
            .ok_or_else(|| CompileError::InvalidReference(reference.to_owned()))?;
        let target = document
            .pointer(pointer)
            .ok_or_else(|| CompileError::InvalidReference(reference.to_owned()))?;
        return resolve_schema(target, document, depth + 1);
    }

    match value {
        Value::Array(values) => values
            .iter()
            .map(|value| resolve_schema(value, document, depth + 1))
            .collect::<Result<Vec<_>, _>>()
            .map(Value::Array),
        Value::Object(object) => object
            .iter()
            .map(|(key, value)| {
                resolve_schema(value, document, depth + 1).map(|value| (key.clone(), value))
            })
            .collect::<Result<Map<_, _>, _>>()
            .map(Value::Object),
        _ => Ok(value.clone()),
    }
}

fn collection_item_schema(schema: Option<&Value>) -> Option<&Value> {
    let schema = schema?;
    if schema.get("type").and_then(Value::as_str) == Some("array") {
        return schema.get("items");
    }
    let properties = schema.get("properties").and_then(Value::as_object)?;
    if let Some(ocs) = properties.get("ocs") {
        return collection_item_schema(Some(ocs));
    }
    if let Some(data) = properties.get("data") {
        return collection_item_schema(Some(data));
    }
    properties.values().find_map(|value| {
        (value.get("type").and_then(Value::as_str) == Some("array"))
            .then(|| value.get("items"))
            .flatten()
    })
}

fn fields_from_schema(schema: &Value, document: &Value) -> Result<Vec<FieldSpec>, CompileError> {
    let schema = resolve_schema(schema, document, 0)?;
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
        .map(|(id, value)| FieldSpec {
            id: id.clone(),
            label: value
                .get("title")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or_else(|| humanize(id)),
            kind: infer_field_kind(id, value),
            required: required.contains(id.as_str()),
            read_only: value
                .get("readOnly")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            format: value
                .get("format")
                .and_then(Value::as_str)
                .map(str::to_owned),
            enum_values: value.get("enum").and_then(Value::as_array).map(|values| {
                values
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_owned)
                    .collect()
            }),
        })
        .collect())
}

fn infer_field_kind(id: &str, value: &Value) -> FieldKind {
    let id = id.to_ascii_lowercase();
    let value_type = value
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let format = value
        .get("format")
        .and_then(Value::as_str)
        .unwrap_or_default();

    if value.get("enum").is_some() {
        return FieldKind::Enumeration;
    }
    if id.contains("currency") {
        return FieldKind::Currency;
    }
    if id.contains("preview") || id.contains("thumbnail") || id.contains("image") {
        return FieldKind::Image;
    }
    if id.contains("file") || id.contains("mimetype") {
        return FieldKind::File;
    }
    if id == "user" || id.ends_with("user_id") || id.ends_with("userid") {
        return FieldKind::UserReference;
    }

    match (value_type, format) {
        ("string", "date") => FieldKind::Date,
        ("string", "date-time") => FieldKind::DateTime,
        ("string", _) if id.contains("description") || id.contains("message") => {
            FieldKind::LongText
        }
        ("string", _) => FieldKind::String,
        ("integer", _) => FieldKind::Integer,
        ("number", _) => FieldKind::Decimal,
        ("boolean", _) => FieldKind::Boolean,
        ("object", _) | ("array", _) => FieldKind::Object,
        _ => FieldKind::Unknown,
    }
}

fn infer_collection_component(resource_id: &str, resource: &ResourceBuilder) -> NativeComponent {
    let id = resource_id.to_ascii_lowercase();
    let field_ids = resource
        .fields
        .keys()
        .map(|value| value.to_ascii_lowercase())
        .collect::<Vec<_>>();

    if contains_any(&id, &["dashboard", "widget"]) {
        NativeComponent::Dashboard
    } else if contains_any(&id, &["file", "folder", "directory"]) {
        NativeComponent::FileBrowser
    } else if contains_any(&id, &["mail", "email", "inbox"]) {
        NativeComponent::Mailbox
    } else if contains_any(&id, &["contact", "addressbook"]) {
        NativeComponent::ContactList
    } else if contains_any(&id, &["task", "chore", "todo"]) {
        NativeComponent::TaskList
    } else if contains_any(&id, &["message", "chat"]) {
        NativeComponent::ChatThread
    } else if contains_any(&id, &["conversation", "room"]) {
        NativeComponent::ConversationList
    } else if contains_any(&id, &["media", "photo", "image", "album", "memory"])
        || field_ids
            .iter()
            .any(|field| contains_any(field, &["preview", "thumbnail", "mimetype", "image"]))
    {
        NativeComponent::MediaGrid
    } else if contains_any(&id, &["calendar", "event"]) {
        NativeComponent::Calendar
    } else if contains_any(&id, &["board", "card", "deck"]) {
        NativeComponent::Board
    } else if contains_any(&id, &["table", "budget", "ledger"]) {
        NativeComponent::DataTable
    } else if contains_any(&id, &["music", "audio", "song", "artist", "album-track"]) {
        NativeComponent::MediaLibrary
    } else if contains_any(&id, &["recipe", "cookbook"]) {
        NativeComponent::RecipeList
    } else if contains_any(&id, &["note", "document", "office", "text"]) {
        NativeComponent::DocumentEditor
    } else if contains_any(&id, &["activity", "timeline"])
        || field_ids.iter().any(|field| field == "timestamp")
    {
        NativeComponent::Timeline
    } else {
        NativeComponent::CollectionList
    }
}

fn schema_contains_array(value: &Value, document: &Value) -> bool {
    if value.get("type").and_then(Value::as_str) == Some("array") {
        return true;
    }
    if let Some(reference) = value.get("$ref").and_then(Value::as_str) {
        return reference
            .strip_prefix('#')
            .and_then(|pointer| document.pointer(pointer))
            .is_some_and(|target| schema_contains_array(target, document));
    }
    value
        .get("properties")
        .and_then(Value::as_object)
        .is_some_and(|properties| {
            properties
                .values()
                .any(|value| schema_contains_array(value, document))
        })
}

fn component_suffix(component: &NativeComponent) -> &'static str {
    match component {
        NativeComponent::Dashboard => "dashboard",
        NativeComponent::FileBrowser => "files",
        NativeComponent::CollectionList => "list",
        NativeComponent::MediaGrid => "media",
        NativeComponent::Detail => "detail",
        NativeComponent::Form => "form",
        NativeComponent::Timeline => "timeline",
        NativeComponent::Calendar => "calendar",
        NativeComponent::Board => "board",
        NativeComponent::Mailbox => "mailbox",
        NativeComponent::ContactList => "contacts",
        NativeComponent::TaskList => "tasks",
        NativeComponent::DataTable => "table",
        NativeComponent::MediaLibrary => "library",
        NativeComponent::RecipeList => "recipes",
        NativeComponent::DocumentEditor => "editor",
        NativeComponent::ConversationList => "conversations",
        NativeComponent::ChatThread => "chat",
    }
}

fn stable_id(value: &str) -> String {
    let mut result = String::new();
    let mut previous_separator = false;
    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            result.push(character.to_ascii_lowercase());
            previous_separator = false;
        } else if !previous_separator && !result.is_empty() {
            result.push('-');
            previous_separator = true;
        }
    }
    result.trim_matches('-').to_owned()
}

fn humanize(value: &str) -> String {
    let words = value
        .replace(['-', '_', '.'], " ")
        .split_whitespace()
        .map(|word| {
            let mut characters = word.chars();
            match characters.next() {
                Some(first) => first.to_uppercase().collect::<String>() + characters.as_str(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>();
    words.join(" ")
}

fn contains_any(value: &str, needles: &[&str]) -> bool {
    needles.iter().any(|needle| value.contains(needle))
}

fn is_version(value: &str) -> bool {
    value
        .strip_prefix('v')
        .is_some_and(|suffix| suffix.chars().all(|character| character.is_ascii_digit()))
}

#[cfg(test)]
mod unit_tests {
    use super::*;

    #[test]
    fn stable_ids_do_not_leak_punctuation() {
        assert_eq!(stable_id("Talk.Rooms/list"), "talk-rooms-list");
    }

    #[test]
    fn semantic_fields_are_classified_before_generic_types() {
        let field = serde_json::json!({ "type": "string" });
        assert_eq!(infer_field_kind("currency", &field), FieldKind::Currency);
        assert_eq!(infer_field_kind("previewUrl", &field), FieldKind::Image);
    }

    #[test]
    fn installed_app_families_select_reusable_components() {
        let cases = [
            ("files", NativeComponent::FileBrowser),
            ("mailboxes", NativeComponent::Mailbox),
            ("contacts", NativeComponent::ContactList),
            ("tasks", NativeComponent::TaskList),
            ("budget-ledger", NativeComponent::DataTable),
            ("music-artists", NativeComponent::MediaLibrary),
            ("cookbook-recipes", NativeComponent::RecipeList),
            ("office-documents", NativeComponent::DocumentEditor),
        ];

        for (resource_id, expected) in cases {
            let resource = ResourceBuilder::new(resource_id, resource_id);
            assert_eq!(infer_collection_component(resource_id, &resource), expected);
        }
    }
}

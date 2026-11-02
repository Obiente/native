use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const NATIVE_SCHEMA_VERSION: &str = "0.1";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppIdentity {
    pub id: String,
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeAppSchema {
    pub schema_version: String,
    pub app: AppIdentity,
    pub confidence: Confidence,
    #[serde(default)]
    pub resources: Vec<ResourceSpec>,
    #[serde(default)]
    pub views: Vec<ViewSpec>,
    #[serde(default)]
    pub actions: Vec<ActionSpec>,
    #[serde(default)]
    pub warnings: Vec<CompilerWarning>,
}

impl NativeAppSchema {
    pub fn empty(app: AppIdentity) -> Self {
        Self {
            schema_version: NATIVE_SCHEMA_VERSION.to_owned(),
            app,
            confidence: Confidence::Low,
            resources: Vec::new(),
            views: Vec::new(),
            actions: Vec::new(),
            warnings: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Confidence {
    Low,
    Medium,
    High,
    Verified,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResourceSpec {
    pub id: String,
    pub name: String,
    pub confidence: Confidence,
    #[serde(default)]
    pub fields: Vec<FieldSpec>,
    #[serde(default)]
    pub evidence: Vec<Evidence>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldSpec {
    pub id: String,
    pub label: String,
    pub kind: FieldKind,
    pub required: bool,
    pub read_only: bool,
    pub format: Option<String>,
    pub enum_values: Option<Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FieldKind {
    String,
    LongText,
    Integer,
    Decimal,
    Boolean,
    Date,
    DateTime,
    Currency,
    Image,
    File,
    UserReference,
    Enumeration,
    Object,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ViewSpec {
    pub id: String,
    pub title: String,
    pub resource_id: String,
    pub component: NativeComponent,
    pub source_action_id: String,
    pub confidence: Confidence,
    #[serde(default)]
    pub evidence: Vec<Evidence>,
}

/// A deliberately small grammar that each platform maps to real native UI.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum NativeComponent {
    Dashboard,
    FileBrowser,
    CollectionList,
    MediaGrid,
    Detail,
    Form,
    Timeline,
    Calendar,
    Board,
    Mailbox,
    ContactList,
    TaskList,
    DataTable,
    MediaLibrary,
    RecipeList,
    DocumentEditor,
    ConversationList,
    ChatThread,
    FeedReader,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionSpec {
    pub id: String,
    pub label: String,
    pub resource_id: String,
    pub binding: ApiBinding,
    pub intent: ActionIntent,
    pub risk: ActionRisk,
    pub requires_confirmation: bool,
    pub confidence: Confidence,
    pub input_schema: Option<Value>,
    #[serde(default)]
    pub evidence: Vec<Evidence>,
    /// Risk class R0-R10; absent in schemas produced before the class existed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub risk_class: Option<OperationRiskClass>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub read_back_action_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idempotency_key_header: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub share_permission_field_id: Option<String>,
}

/// What can go wrong when an operation runs; mirrors the Kotlin `DynamicOperationRiskClass`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OperationRiskClass {
    Read,
    IdempotentStateSet,
    ReversibleScalarEdit,
    PartialUpdate,
    Replacement,
    Create,
    ExternalEffect,
    Destructive,
    Sharing,
    Administration,
    Credential,
}

impl OperationRiskClass {
    /// The R0-R10 code used in diagnostics and documentation.
    pub fn code(self) -> &'static str {
        match self {
            Self::Read => "R0",
            Self::IdempotentStateSet => "R1",
            Self::ReversibleScalarEdit => "R2",
            Self::PartialUpdate => "R3",
            Self::Replacement => "R4",
            Self::Create => "R5",
            Self::ExternalEffect => "R6",
            Self::Destructive => "R7",
            Self::Sharing => "R8",
            Self::Administration => "R9",
            Self::Credential => "R10",
        }
    }

    /// Classifies from the method and the operation's own words; never from an app identity.
    /// Route placeholders are ignored so `{token}` is not mistaken for a credential.
    pub fn classify(
        method: HttpMethod,
        path: &str,
        operation_id: &str,
        label: &str,
        has_body: bool,
    ) -> Self {
        if method == HttpMethod::Get {
            return Self::Read;
        }
        let mut words = std::collections::BTreeSet::new();
        let literal_path = path
            .split('/')
            .filter(|segment| !(segment.starts_with('{') && segment.ends_with('}')))
            .collect::<Vec<_>>()
            .join("/");
        for source in [literal_path.as_str(), operation_id, label] {
            let mut current = String::new();
            let mut previous_lower = false;
            for character in source.chars() {
                if character.is_ascii_alphanumeric() {
                    if character.is_ascii_uppercase() && previous_lower && !current.is_empty() {
                        words.insert(std::mem::take(&mut current));
                    }
                    previous_lower = character.is_ascii_lowercase() || character.is_ascii_digit();
                    current.push(character.to_ascii_lowercase());
                } else {
                    previous_lower = false;
                    if !current.is_empty() {
                        words.insert(std::mem::take(&mut current));
                    }
                }
            }
            if !current.is_empty() {
                words.insert(current);
            }
        }
        let any = |candidates: &[&str]| candidates.iter().any(|word| words.contains(*word));
        if any(&[
            "password",
            "passwords",
            "passphrase",
            "credential",
            "credentials",
            "secret",
            "secrets",
            "apikey",
            "apikeys",
        ]) || (words.contains("api") && any(&["key", "keys"]))
        {
            Self::Credential
        } else if any(&[
            "admin",
            "administration",
            "administrator",
            "provisioning",
            "install",
            "uninstall",
        ]) {
            Self::Administration
        } else if any(&["share", "shares", "sharing", "reshare", "unshare"]) {
            Self::Sharing
        } else if any(&["send", "merge", "notify", "invite", "publish"]) {
            Self::ExternalEffect
        } else {
            match method {
                HttpMethod::Get => Self::Read,
                HttpMethod::Delete => Self::Destructive,
                HttpMethod::Patch => Self::PartialUpdate,
                HttpMethod::Put if has_body => Self::Replacement,
                HttpMethod::Put => Self::IdempotentStateSet,
                HttpMethod::Post => Self::Create,
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiBinding {
    pub method: HttpMethod,
    pub path: String,
    pub operation_id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum HttpMethod {
    Get,
    Post,
    Put,
    Patch,
    Delete,
}

impl HttpMethod {
    pub fn is_read_only(self) -> bool {
        matches!(self, Self::Get)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ActionIntent {
    List,
    Read,
    Create,
    Update,
    Delete,
    Execute,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ActionRisk {
    ReadOnly,
    Mutating,
    Destructive,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Evidence {
    pub source: EvidenceSource,
    pub detail: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum EvidenceSource {
    AppMetadata,
    Capability,
    OpenApi,
    Accessibility,
    NetworkObservation,
    VerifiedAdapter,
    LocalInference,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CompilerWarning {
    pub code: String,
    pub message: String,
}

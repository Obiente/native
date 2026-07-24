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

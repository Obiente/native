use nextcloud_native_runtime::{
    ActionRisk, AppIdentity, Confidence, DiscoverySnapshot, EvidenceSource, NativeComponent,
    NativeSchemaCompiler, NavigationEntry, OpenApiCompiler,
};
use pretty_assertions::assert_eq;
use serde_json::{Value, json};

fn snapshot(app_id: &str, name: &str, openapi: Value) -> DiscoverySnapshot {
    DiscoverySnapshot {
        app: AppIdentity {
            id: app_id.to_owned(),
            name: name.to_owned(),
            version: "test-version".to_owned(),
        },
        navigation: Vec::new(),
        capabilities: json!({}),
        openapi,
    }
}

fn list_response(component: &str) -> Value {
    json!({
        "description": "OK",
        "content": {
            "application/json": {
                "schema": {
                    "type": "object",
                    "properties": {
                        "ocs": {
                            "type": "object",
                            "properties": {
                                "data": {
                                    "type": "array",
                                    "items": { "$ref": format!("#/components/schemas/{component}") }
                                }
                            }
                        }
                    }
                }
            }
        }
    })
}

#[test]
fn memories_media_is_compiled_to_a_native_grid() {
    let openapi = json!({
        "openapi": "3.0.3",
        "paths": {
            "/ocs/v2.php/apps/memories/api/v1/timeline": {
                "get": {
                    "operationId": "memories.timeline.list",
                    "summary": "Browse the timeline",
                    "tags": ["media"],
                    "responses": { "200": list_response("Media") }
                }
            }
        },
        "components": {
            "schemas": {
                "Media": {
                    "type": "object",
                    "required": ["id", "name", "takenAt"],
                    "properties": {
                        "id": { "type": "integer", "readOnly": true },
                        "name": { "type": "string" },
                        "takenAt": { "type": "string", "format": "date-time" },
                        "mimetype": { "type": "string" },
                        "previewUrl": { "type": "string", "format": "uri" }
                    }
                }
            }
        }
    });

    let schema = OpenApiCompiler
        .compile(&snapshot("memories", "Memories", openapi))
        .expect("Memories schema should compile");

    assert_eq!(schema.confidence, Confidence::High);
    assert_eq!(schema.resources[0].id, "media");
    assert_eq!(schema.resources[0].fields.len(), 5);
    assert_eq!(schema.views[0].component, NativeComponent::MediaGrid);
    assert_eq!(schema.actions[0].requires_confirmation, false);
}

#[test]
fn cospend_generates_expense_list_form_and_safe_write_binding() {
    let openapi = json!({
        "openapi": "3.0.3",
        "paths": {
            "/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}/expenses": {
                "get": {
                    "operationId": "cospend.expenses.list",
                    "summary": "List expenses",
                    "tags": ["expenses"],
                    "responses": { "200": list_response("Expense") }
                },
                "post": {
                    "operationId": "cospend.expenses.create",
                    "summary": "Create expense",
                    "tags": ["expenses"],
                    "requestBody": {
                        "content": {
                            "application/json": {
                                "schema": { "$ref": "#/components/schemas/ExpenseInput" }
                            }
                        }
                    },
                    "responses": { "201": { "description": "Created" } }
                }
            }
        },
        "components": {
            "schemas": {
                "Expense": {
                    "type": "object",
                    "required": ["id", "amount", "currency", "description"],
                    "properties": {
                        "id": { "type": "integer", "readOnly": true },
                        "amount": { "type": "number", "format": "double" },
                        "currency": { "type": "string" },
                        "description": { "type": "string" },
                        "paidByUserId": { "type": "string" }
                    }
                },
                "ExpenseInput": {
                    "type": "object",
                    "required": ["amount", "description"],
                    "properties": {
                        "amount": { "type": "number" },
                        "description": { "type": "string" }
                    }
                }
            }
        }
    });

    let schema = OpenApiCompiler
        .compile(&snapshot("cospend", "Cospend", openapi))
        .expect("Cospend schema should compile");

    assert!(
        schema
            .views
            .iter()
            .any(|view| view.component == NativeComponent::CollectionList)
    );
    assert!(
        schema
            .views
            .iter()
            .any(|view| view.component == NativeComponent::Form)
    );

    let create = schema
        .actions
        .iter()
        .find(|action| action.binding.operation_id == "cospend.expenses.create")
        .expect("create action");
    assert_eq!(create.risk, ActionRisk::Mutating);
    assert!(create.requires_confirmation);
    assert!(create.input_schema.is_some());
}

#[test]
fn talk_distinguishes_conversations_from_chat_messages() {
    let openapi = json!({
        "openapi": "3.0.3",
        "paths": {
            "/ocs/v2.php/apps/spreed/api/v4/room": {
                "get": {
                    "operationId": "talk.conversations.list",
                    "summary": "List conversations",
                    "tags": ["conversations"],
                    "responses": { "200": list_response("Conversation") }
                }
            },
            "/ocs/v2.php/apps/spreed/api/v1/chat/{token}": {
                "get": {
                    "operationId": "talk.messages.list",
                    "summary": "List chat messages",
                    "tags": ["messages"],
                    "responses": { "200": list_response("Message") }
                }
            }
        },
        "components": {
            "schemas": {
                "Conversation": {
                    "type": "object",
                    "properties": {
                        "token": { "type": "string" },
                        "displayName": { "type": "string" },
                        "lastActivity": { "type": "integer" }
                    }
                },
                "Message": {
                    "type": "object",
                    "properties": {
                        "id": { "type": "integer" },
                        "actorDisplayName": { "type": "string" },
                        "message": { "type": "string" },
                        "timestamp": { "type": "integer" }
                    }
                }
            }
        }
    });

    let schema = OpenApiCompiler
        .compile(&snapshot("spreed", "Talk", openapi))
        .expect("Talk schema should compile");

    assert!(schema.views.iter().any(|view| {
        view.resource_id == "conversations" && view.component == NativeComponent::ConversationList
    }));
    assert!(schema.views.iter().any(|view| {
        view.resource_id == "messages" && view.component == NativeComponent::ChatThread
    }));
}

#[test]
fn installed_app_without_openapi_gets_read_only_metadata_fallback() {
    let mut discovered = snapshot("chores", "Chores", json!({}));
    discovered.navigation.push(NavigationEntry {
        id: "chores".to_owned(),
        name: "Chores".to_owned(),
        route: "/index.php/apps/chores".to_owned(),
        icon_url: None,
    });

    let schema = OpenApiCompiler
        .compile(&discovered)
        .expect("metadata-only discovery should still compile");

    assert_eq!(schema.confidence, Confidence::Low);
    assert_eq!(schema.resources.len(), 1);
    assert_eq!(schema.resources[0].id, "app-metadata");
    assert!(
        schema.resources[0]
            .fields
            .iter()
            .all(|field| field.read_only)
    );
    assert_eq!(schema.views.len(), 1);
    assert_eq!(schema.views[0].component, NativeComponent::Detail);
    assert_eq!(
        schema.resources[0].evidence[0].source,
        EvidenceSource::AppMetadata
    );
    assert!(schema.actions.is_empty());
    assert_eq!(schema.warnings[0].code, "metadata-only");
}

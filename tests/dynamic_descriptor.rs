use nextcloud_native_runtime::{
    ActionIntent, AuthKind, DescriptorValidationError, DynamicAppDescriptor, DynamicCompileError,
    DynamicDescriptorCompiler, DynamicDiscoveryInput, DynamicLinkTarget, HttpMethod, LayoutKind,
    OpenApiTrust, ProvenanceKind,
};
use pretty_assertions::assert_eq;

fn openapi_input() -> DynamicDiscoveryInput {
    serde_json::from_str(include_str!("fixtures/dynamic_openapi_input.json"))
        .expect("valid OpenAPI discovery fixture")
}

fn observation_input() -> DynamicDiscoveryInput {
    serde_json::from_str(include_str!("fixtures/dynamic_observation_input.json"))
        .expect("valid observation discovery fixture")
}

#[test]
fn imports_advertised_openapi_into_complete_dynamic_contract() {
    let descriptor = DynamicDescriptorCompiler
        .compile(&openapi_input())
        .expect("advertised OpenAPI should compile");

    assert_eq!(descriptor.descriptor_version, "1.0");
    assert_eq!(descriptor.capabilities[0].id, "tables.apiVersion");
    assert_eq!(descriptor.resources.len(), 1);
    assert!(
        descriptor.resources[0]
            .fields
            .iter()
            .any(|field| field.id == "title")
    );
    assert!(
        descriptor
            .layouts
            .iter()
            .any(|layout| layout.kind == LayoutKind::List)
    );
    assert!(
        descriptor
            .layouts
            .iter()
            .any(|layout| layout.kind == LayoutKind::Detail)
    );
    assert_eq!(descriptor.forms.len(), 1);

    let create = descriptor
        .actions
        .iter()
        .find(|action| action.binding.method == HttpMethod::Post)
        .expect("documented create action");
    assert!(create.requires_confirmation);
    assert_eq!(
        create
            .binding
            .body
            .as_ref()
            .expect("request body")
            .content_type,
        "application/json"
    );
    assert_eq!(create.binding.auth[0].kind, AuthKind::Basic);
    assert!(
        create
            .binding
            .ocs
            .as_ref()
            .expect("OCS binding")
            .api_request_header
    );
    assert!(
        create
            .provenance
            .iter()
            .any(|item| item.kind == ProvenanceKind::AdvertisedOpenApi)
    );

    let list = descriptor
        .actions
        .iter()
        .find(|action| {
            action.binding.method == HttpMethod::Get && action.intent == ActionIntent::List
        })
        .expect("list action");
    assert!(
        list.binding
            .query_parameters
            .iter()
            .any(|item| item.name == "limit")
    );
    assert_eq!(
        list.binding
            .ocs
            .as_ref()
            .expect("OCS metadata")
            .format_query_parameter
            .as_deref(),
        Some("format"),
    );
    assert!(descriptor.links.iter().any(|link| {
        link.source_field_id == "iconUrl"
            && matches!(
                link.target,
                DynamicLinkTarget::FieldUrl {
                    allow_external: false
                }
            )
    }));
    let wire = serde_json::to_value(&descriptor).expect("serialize dynamic descriptor");
    assert_eq!(
        wire.pointer("/links/0/target/allowExternal")
            .and_then(serde_json::Value::as_bool),
        Some(false),
    );
    descriptor
        .validate()
        .expect("compiled descriptor remains valid after serialization boundary");
}

#[test]
fn successful_json_read_infers_only_read_only_fields_and_get_action() {
    let descriptor = DynamicDescriptorCompiler
        .compile(&observation_input())
        .expect("successful JSON observation should compile");

    assert!(descriptor.forms.is_empty());
    assert!(
        descriptor
            .actions
            .iter()
            .all(|action| action.binding.method == HttpMethod::Get)
    );
    assert!(
        descriptor.resources[0]
            .fields
            .iter()
            .all(|field| field.read_only)
    );
    assert!(
        descriptor.resources[0]
            .fields
            .iter()
            .any(|field| field.id == "dueDate" && field.nullable)
    );
    assert!(
        descriptor.resources[0]
            .provenance
            .iter()
            .any(|item| item.kind == ProvenanceKind::SuccessfulReadObservation)
    );
    assert!(
        descriptor
            .permissions
            .iter()
            .any(|permission| permission.id == "chores.read")
    );
}

#[test]
fn rejects_cross_origin_openapi_server_even_when_document_was_advertised_locally() {
    let mut input = openapi_input();
    input.advertised_openapi.as_mut().expect("OpenAPI").document["servers"] =
        serde_json::json!([{ "url": "https://attacker.example/api" }]);

    let error = DynamicDescriptorCompiler
        .compile(&input)
        .expect_err("cross-origin must fail");

    assert!(matches!(
        error,
        DynamicCompileError::Validation(DescriptorValidationError::CrossOrigin(_))
    ));
}

#[test]
fn trusted_equivalent_server_paths_rebase_to_the_authenticated_origin() {
    let mut input = openapi_input();
    let advertised = input.advertised_openapi.as_mut().expect("OpenAPI");
    advertised.document_url =
        "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json".to_owned();
    advertised.trust = OpenApiTrust::AppStoreLinkedExactGitHubTag;
    advertised.document["servers"] = serde_json::json!([
        { "url": "http://localhost:8000/ocs/v2.php/apps/tables/api/2" },
        { "url": "{protocol}://{server}/ocs/v2.php/apps/tables/api/2/" }
    ]);

    let descriptor = DynamicDescriptorCompiler
        .compile(&input)
        .expect("equivalent documented environments should compile through the trusted boundary");

    assert!(descriptor.actions.iter().all(|action| {
        action
            .binding
            .path
            .starts_with("/ocs/v2.php/apps/tables/api/2/")
    }));
}

#[test]
fn trusted_distinct_server_paths_remain_fail_closed() {
    let mut input = openapi_input();
    let advertised = input.advertised_openapi.as_mut().expect("OpenAPI");
    advertised.document_url =
        "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json".to_owned();
    advertised.trust = OpenApiTrust::AppStoreLinkedExactGitHubTag;
    advertised.document["servers"] = serde_json::json!([
        { "url": "https://one.example/ocs/v2.php/apps/tables/api/2" },
        { "url": "https://two.example/ocs/v2.php/apps/tables/api/3" }
    ]);

    let error = DynamicDescriptorCompiler
        .compile(&input)
        .expect_err("distinct path bases must not be guessed");

    assert!(matches!(
        error,
        DynamicCompileError::ConflictingServerBases(_)
    ));
}

#[test]
fn trusted_external_schema_references_remain_opaque_without_fetching() {
    let mut input = openapi_input();
    let advertised = input.advertised_openapi.as_mut().expect("OpenAPI");
    advertised.document_url =
        "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json".to_owned();
    advertised.trust = OpenApiTrust::AppStoreLinkedExactGitHubTag;
    advertised.document["components"]["schemas"]["Table"] =
        serde_json::json!({ "$ref": "objects.yaml#/Table" });

    let descriptor = DynamicDescriptorCompiler
        .compile(&input)
        .expect("trusted endpoints should survive opaque sibling schema references");

    assert!(!descriptor.actions.is_empty());
    assert!(
        descriptor
            .warnings
            .iter()
            .any(|warning| warning.code == "opaque-external-schema-reference")
    );
}

#[test]
fn rejects_cross_origin_openapi_document_advertisement() {
    let mut input = openapi_input();
    input
        .advertised_openapi
        .as_mut()
        .expect("OpenAPI")
        .document_url = "https://attacker.example/tables.json".to_owned();

    let error = DynamicDescriptorCompiler
        .compile(&input)
        .expect_err("cross-origin document must fail");

    assert!(matches!(
        error,
        DynamicCompileError::Validation(DescriptorValidationError::CrossOrigin(_))
    ));
}

#[test]
fn rejects_successful_reads_outside_the_approved_app_prefix() {
    let mut input = observation_input();
    input.successful_reads[0].path = "/ocs/v2.php/cloud/capabilities".to_owned();

    let error = DynamicDescriptorCompiler
        .compile(&input)
        .expect_err("unapproved read must fail");

    assert!(matches!(
        error,
        DynamicCompileError::Validation(DescriptorValidationError::UnapprovedEndpoint(_))
    ));
}

#[test]
fn rejects_failed_or_non_json_observations() {
    let mut input = observation_input();
    input.successful_reads[0].status = 500;

    assert!(matches!(
        DynamicDescriptorCompiler.compile(&input),
        Err(DynamicCompileError::InvalidReadObservation(_))
    ));

    let mut input = observation_input();
    input.successful_reads[0].content_type = "text/html".to_owned();
    assert!(matches!(
        DynamicDescriptorCompiler.compile(&input),
        Err(DynamicCompileError::InvalidReadObservation(_))
    ));
}

#[test]
fn serialized_descriptor_cannot_be_tampered_into_an_observed_write() {
    let descriptor = DynamicDescriptorCompiler
        .compile(&observation_input())
        .expect("observation descriptor");
    let json = serde_json::to_string(&descriptor).expect("serialize descriptor");
    let mut decoded: DynamicAppDescriptor = serde_json::from_str(&json).expect("decode descriptor");
    decoded.actions[0].binding.method = HttpMethod::Post;

    assert!(matches!(
        decoded.validate(),
        Err(DescriptorValidationError::UnprovenWrite(_))
    ));
}

#[test]
fn serialized_descriptor_cannot_escape_approved_paths() {
    let mut descriptor = DynamicDescriptorCompiler
        .compile(&openapi_input())
        .expect("OpenAPI descriptor");
    descriptor.actions[0].binding.path = "/remote.php/dav/files/alice".to_owned();

    assert!(matches!(
        descriptor.validate(),
        Err(DescriptorValidationError::UnapprovedEndpoint(_))
    ));
}

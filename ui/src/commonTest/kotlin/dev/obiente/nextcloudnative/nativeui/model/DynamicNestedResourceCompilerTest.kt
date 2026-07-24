package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DynamicNestedResourceCompilerTest {
    @Test
    fun `camel collapsed operation names retain nested account resources without self loops`() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("budget", "Budget", "2.39.1"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/budget/api"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "/apps/budget/openapi.json",
                    document = Json.parseToJsonElement(BUDGET_ACCOUNT_CONTRACT),
                ),
            ),
        )

        assertEquals(
            listOf("accounts"),
            descriptor.planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::resourceId),
        )
        val childPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "accounts",
                recordId = "account-7",
                actionSafeIdentity = false,
            ),
        )

        assertEquals(
            setOf("balance-history", "metrics"),
            childPlan.contextualChildDestinations.mapTo(mutableSetOf(), DynamicNavigationDestination::resourceId),
        )
        assertEquals(
            setOf(mapOf("id" to "account-7")),
            childPlan.contextualChildDestinations
                .mapTo(mutableSetOf(), DynamicNavigationDestination::pathParameterValues),
        )
        assertFalse(childPlan.contextualChildDestinations.any { it.resourceId == "accounts" })
        assertFalse(childPlan.contextualFormActions.isNotEmpty())
    }

    private companion object {
        val BUDGET_ACCOUNT_CONTRACT = """
            {
              "openapi": "3.0.3",
              "info": {"title": "budget", "version": "2.39.1"},
              "paths": {
                "/apps/budget/api/accounts": {
                  "get": {
                    "operationId": "route-account-index",
                    "tags": ["account"],
                    "responses": {
                      "200": {
                        "description": "Accounts",
                        "content": {"application/json": {"schema": {
                          "type": "array",
                          "items": {"type": "object", "properties": {
                            "id": {"type": "integer"},
                            "name": {"type": "string"},
                            "balance": {"type": "number"}
                          }}
                        }}}
                      }
                    }
                  }
                },
                "/apps/budget/api/accounts/{id}/balance-history": {
                  "get": {
                    "operationId": "route-account-getbalancehistory",
                    "tags": ["account"],
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}
                    ],
                    "responses": {
                      "200": {
                        "description": "Balance history",
                        "content": {"application/json": {"schema": {
                          "type": "array",
                          "items": {"type": "object", "properties": {
                            "date": {"type": "string", "format": "date"},
                            "balance": {"type": "number"}
                          }}
                        }}}
                      }
                    }
                  }
                },
                "/apps/budget/api/accounts/{id}/metrics": {
                  "get": {
                    "operationId": "route-account-getmetrics",
                    "tags": ["account"],
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}
                    ],
                    "responses": {
                      "200": {
                        "description": "Metrics",
                        "content": {"application/json": {"schema": {
                          "type": "object",
                          "properties": {
                            "totalTransactions": {"type": "integer"},
                            "thisMonthExpenses": {"type": "number"}
                          }
                        }}}
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}

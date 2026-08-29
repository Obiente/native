package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun Map<String, DynamicAction>.assertAction(
    id: String,
    effect: ActionEffect,
    intent: ActionIntent,
    risk: ActionRisk,
    requiresConfirmation: Boolean,
) {
    val action = assertNotNull(this[id])
    assertEquals(effect, action.effect, id)
    assertEquals(intent, action.intent, id)
    assertEquals(risk, action.risk, id)
    assertEquals(requiresConfirmation, action.requiresConfirmation, id)
}

internal val DYNAMIC_ACTION_SEMANTIC_EXTENSION_PATHS = """
    "/apps/example/api/widgets/import":{
      "post":{
        "operationId":"widgets-import",
        "summary":"Import widgets from URL",
        "requestBody":{"required":true,"content":{"application/json":{"schema":{
          "type":"object",
          "required":["url"],
          "properties":{"url":{"type":"string","format":"uri"}}
        }}}},
        "responses":{"200":{"description":"OK"}}
      }
    },
    "/apps/example/api/categories":{
      "get":{
        "operationId":"categories-list",
        "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
          "type":"array",
          "items":{"type":"string"}
        }}}}}
      }
    },
    "/apps/example/api/category/{category}":{
      "parameters":[{"name":"category","in":"path","required":true,"schema":{"type":"string"}}],
      "get":{
        "operationId":"category-items",
        "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
          "type":"array",
          "items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"}}}
        }}}}}
      },
      "put":{
        "operationId":"category-rename",
        "requestBody":{"required":true,"content":{"application/json":{"schema":{
          "type":"object",
          "required":["name"],
          "properties":{"name":{"type":"string"}}
        }}}},
        "responses":{"200":{"description":"OK"}}
      }
    },
""".trimIndent()

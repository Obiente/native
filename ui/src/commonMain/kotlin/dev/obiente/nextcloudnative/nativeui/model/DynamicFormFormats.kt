package dev.obiente.nextcloudnative.nativeui.model

/** Set-like user settings rendered one item per line; duplicate entries are normalized away. */
const val DYNAMIC_STRING_LIST_FORMAT = "nextcloud-string-list"

/**
 * Ordered contract-declared text arrays such as ingredients or instructions.
 *
 * This marker is added only for signed/advertised `type: array` schemas with string items. It
 * never upgrades an untyped object into an editable list, and duplicate entries remain data.
 */
const val DYNAMIC_STRING_ARRAY_FORMAT = "nextcloud-string-array"

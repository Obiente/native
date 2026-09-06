package dev.obiente.nextcloudnative.app

internal fun desktopAccountSecretReference(accountId: NextcloudAccountId): DesktopSecretReference =
    DesktopSecretReference(
        targetName = "Obiente/NextcloudNative/session/v2/${accountId.storageKey}",
        label = "Nextcloud Native account credential",
        attributes = linkedMapOf(
            "application" to "dev.obiente.nextcloudnative",
            "purpose" to "account-session",
            "account" to accountId.storageKey,
            "schema" to "2",
        ),
    )

internal fun desktopAccountCredentialRollbackReference(accountId: NextcloudAccountId): DesktopSecretReference =
    DesktopSecretReference(
        targetName = "Obiente/NextcloudNative/session-rollback/v1/${accountId.storageKey}",
        label = "Nextcloud Native account credential rollback",
        attributes = linkedMapOf(
            "application" to "dev.obiente.nextcloudnative",
            "purpose" to "account-session-rollback",
            "account" to accountId.storageKey,
            "schema" to "1",
        ),
    )

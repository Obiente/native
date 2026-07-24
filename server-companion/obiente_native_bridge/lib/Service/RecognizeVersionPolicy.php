<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\ObienteNativeBridge\Service;

/**
 * Pure compatibility policy kept separate so it can be tested without a
 * bootstrapped server. Recognize first published ApiKeyManager in v11.0.0.
 */
final class RecognizeVersionPolicy {
	public const MINIMUM_VERSION = '11.0.0';

	public static function supports(bool $enabled, ?string $version, bool $managerAvailable): bool {
		return self::unavailableReason($enabled, $version, $managerAvailable) === null;
	}

	public static function unavailableReason(bool $enabled, ?string $version, bool $managerAvailable): ?string {
		if (!$enabled) {
			return 'recognize_disabled';
		}

		if ($version === null || version_compare($version, self::MINIMUM_VERSION, '<')) {
			return 'recognize_version_unsupported';
		}

		if (!$managerAvailable) {
			return 'recognize_public_api_unavailable';
		}

		return null;
	}
}

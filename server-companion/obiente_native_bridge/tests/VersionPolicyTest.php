<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

require_once __DIR__ . '/../lib/Service/RecognizeVersionPolicy.php';

use OCA\ObienteNativeBridge\Service\RecognizeVersionPolicy;

/** @param mixed $actual */
function assertSameValue(mixed $expected, mixed $actual, string $message): void {
	if ($expected !== $actual) {
		fwrite(STDERR, $message . ': expected ' . var_export($expected, true) . ', got ' . var_export($actual, true) . PHP_EOL);
		exit(1);
	}
}

assertSameValue(
	'recognize_disabled',
	RecognizeVersionPolicy::unavailableReason(false, null, false),
	'disabled Recognize must stay unavailable',
);
assertSameValue(
	'recognize_version_unsupported',
	RecognizeVersionPolicy::unavailableReason(true, '10.0.7', false),
	'Recognize 10 predates the public key manager',
);
assertSameValue(
	'recognize_public_api_unavailable',
	RecognizeVersionPolicy::unavailableReason(true, '11.0.0', false),
	'a supported version still needs the public manager',
);
assertSameValue(
	null,
	RecognizeVersionPolicy::unavailableReason(true, '11.0.0', true),
	'the first supported release must be accepted',
);
assertSameValue(true, RecognizeVersionPolicy::supports(true, '12.1.0-dev.0', true), 'newer compatible release');

fwrite(STDOUT, "RecognizeVersionPolicy: ok\n");

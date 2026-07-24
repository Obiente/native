<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\ObienteNativeBridge;

use OCA\ObienteNativeBridge\AppInfo\Application;
use OCA\ObienteNativeBridge\Service\RecognizeIntegration;
use OCP\Capabilities\ICapability;

final class Capabilities implements ICapability {
	public function __construct(
		private readonly RecognizeIntegration $recognize,
	) {
	}

	/**
	 * @return array{obiente_native_bridge: array{
	 *     api_version: int,
	 *     recognize: array{
	 *         available: bool,
	 *         reason: string|null,
	 *         recognize_version: string|null,
	 *         minimum_recognize_version: string,
	 *         token_endpoint: string,
	 *         method: string,
	 *         ocs_api_request_required: bool,
	 *         dav_header: string,
	 *         expires_in: int
	 *     }
	 * }}
	 */
	#[\Override]
	public function getCapabilities(): array {
		$status = $this->recognize->status();

		return [
			Application::APP_ID => [
				'api_version' => 1,
				'recognize' => [
					'available' => $status['available'],
					'reason' => $status['reason'],
					'recognize_version' => $status['version'],
					'minimum_recognize_version' => RecognizeIntegration::MINIMUM_VERSION,
					'token_endpoint' => '/ocs/v2.php/apps/' . Application::APP_ID . '/api/v1/recognize/token',
					'method' => 'POST',
					'ocs_api_request_required' => true,
					'dav_header' => RecognizeIntegration::DAV_HEADER,
					'expires_in' => RecognizeIntegration::TOKEN_LIFETIME_SECONDS,
				],
			],
		];
	}
}

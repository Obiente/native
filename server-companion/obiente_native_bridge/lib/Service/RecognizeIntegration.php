<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\ObienteNativeBridge\Service;

use OCA\ObienteNativeBridge\Exception\RecognizeUnavailableException;
use OCA\Recognize\Public\ApiKeyManager;
use OCP\App\IAppManager;
use OCP\AppFramework\Utility\ITimeFactory;

final class RecognizeIntegration {
	public const MINIMUM_VERSION = RecognizeVersionPolicy::MINIMUM_VERSION;
	public const DAV_HEADER = 'X-Recognize-Api-Key';

	/**
	 * Recognize's DAV PropFindPlugin accepts generated keys for exactly one day.
	 * This is intentionally duplicated instead of depending on that internal
	 * plugin class. ApiKeyManager is Recognize's only public API used here.
	 */
	public const TOKEN_LIFETIME_SECONDS = 60 * 60 * 24;

	public function __construct(
		private readonly IAppManager $appManager,
		private readonly ITimeFactory $timeFactory,
		private readonly ?ApiKeyManager $apiKeyManager,
	) {
	}

	/**
	 * @return array{available: bool, reason: string|null, version: string|null}
	 */
	public function status(): array {
		$enabled = $this->appManager->isEnabledForUser('recognize');
		$version = $enabled ? $this->appManager->getAppVersion('recognize') : null;
		$version = $version !== '' ? $version : null;
		$reason = RecognizeVersionPolicy::unavailableReason(
			$enabled,
			$version,
			$this->apiKeyManager !== null,
		);

		return [
			'available' => $reason === null,
			'reason' => $reason,
			'version' => $version,
		];
	}

	/**
	 * @return array{
	 *     token: string,
	 *     header_name: string,
	 *     expires_in: int,
	 *     expires_at: string,
	 *     recognize_version: string
	 * }
	 * @throws RecognizeUnavailableException
	 * @throws \JsonException
	 */
	public function mint(): array {
		$status = $this->status();
		if (!$status['available'] || $this->apiKeyManager === null || $status['version'] === null) {
			throw new RecognizeUnavailableException($status['reason'] ?? 'recognize_unavailable');
		}

		$issuedAt = $this->timeFactory->now()->getTimestamp();

		return [
			'token' => $this->apiKeyManager->generateApiKey(),
			'header_name' => self::DAV_HEADER,
			'expires_in' => self::TOKEN_LIFETIME_SECONDS,
			'expires_at' => (new \DateTimeImmutable('@' . ($issuedAt + self::TOKEN_LIFETIME_SECONDS)))->format(\DATE_ATOM),
			'recognize_version' => $status['version'],
		];
	}
}

<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\ObienteNativeBridge\Controller;

use OCA\ObienteNativeBridge\Exception\RecognizeUnavailableException;
use OCA\ObienteNativeBridge\Service\RecognizeIntegration;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\OpenAPI;
use OCP\AppFramework\Http\DataResponse;
use OCP\AppFramework\OCSController;
use OCP\IRequest;
use Psr\Log\LoggerInterface;

final class RecognizeTokenController extends OCSController {
	public function __construct(
		string $appName,
		IRequest $request,
		private readonly RecognizeIntegration $recognize,
		private readonly LoggerInterface $logger,
		private readonly ?string $userId,
	) {
		parent::__construct($appName, $request);
	}

	/**
	 * Mint the current authenticated account's short-lived secondary key for
	 * Recognize DAV requests. The key never replaces normal account auth.
	 *
	 * @return DataResponse<Http::STATUS_OK, array{
	 *     token: string,
	 *     header_name: string,
	 *     expires_in: int,
	 *     expires_at: string,
	 *     recognize_version: string
	 * }, array{}>|DataResponse<Http::STATUS_UNAUTHORIZED|Http::STATUS_SERVICE_UNAVAILABLE, array{
	 *     error: string,
	 *     available: false
	 * }, array{}>
	 *
	 * 200: Secondary DAV key minted
	 * 401: Normal Nextcloud authentication is missing
	 * 503: Recognize or its supported public key API is unavailable
	 */
	#[NoAdminRequired]
	#[OpenAPI(scope: OpenAPI::SCOPE_DEFAULT, tags: ['recognize'])]
	public function mint(): DataResponse {
		if ($this->userId === null || $this->userId === '') {
			return $this->noStore(new DataResponse([
				'error' => 'authentication_required',
				'available' => false,
			], Http::STATUS_UNAUTHORIZED));
		}

		try {
			return $this->noStore(new DataResponse($this->recognize->mint()));
		} catch (RecognizeUnavailableException $exception) {
			return $this->noStore(new DataResponse([
				'error' => $exception->getMessage(),
				'available' => false,
			], Http::STATUS_SERVICE_UNAVAILABLE));
		} catch (\JsonException $exception) {
			// Never log the generated key or response body.
			$this->logger->error('Recognize failed to generate a secondary DAV key', [
				'exception' => $exception,
			]);

			return $this->noStore(new DataResponse([
				'error' => 'recognize_key_generation_failed',
				'available' => false,
			], Http::STATUS_SERVICE_UNAVAILABLE));
		}
	}

	private function noStore(DataResponse $response): DataResponse {
		$response->addHeader('Cache-Control', 'no-store, private');
		$response->addHeader('Pragma', 'no-cache');
		$response->addHeader('Referrer-Policy', 'no-referrer');
		return $response;
	}
}

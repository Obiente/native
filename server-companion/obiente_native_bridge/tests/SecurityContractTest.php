<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Obiente
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

$root = dirname(__DIR__);
$routes = require $root . '/appinfo/routes.php';
$controller = file_get_contents($root . '/lib/Controller/RecognizeTokenController.php');
$capabilities = file_get_contents($root . '/lib/Capabilities.php');
$openApi = json_decode((string)file_get_contents($root . '/openapi.json'), true, 512, JSON_THROW_ON_ERROR);

function requireContract(bool $condition, string $message): void {
	if (!$condition) {
		fwrite(STDERR, $message . PHP_EOL);
		exit(1);
	}
}

requireContract(isset($routes['ocs']) && count($routes['ocs']) === 1, 'Exactly one OCS route is allowed');
$route = $routes['ocs'][0];
requireContract($route['verb'] === 'POST', 'The key route must be POST-only');
requireContract($route['url'] === '/api/v1/recognize/token', 'The key route path changed unexpectedly');
requireContract(!str_contains($route['url'], '{'), 'The key route must not accept URL parameters');

requireContract($controller !== false, 'Controller source is unreadable');
requireContract(str_contains($controller, '#[NoAdminRequired]'), 'Regular authenticated users must be explicitly allowed');
requireContract(!str_contains($controller, 'NoCSRFRequired'), 'The OCS route must retain framework CSRF protection');
requireContract(!str_contains($controller, 'PublicPage'), 'The key route must never be public');
requireContract(!str_contains($controller, '#[CORS'), 'The key route must not enable cross-origin access');
requireContract(str_contains($controller, "'Cache-Control', 'no-store, private'"), 'Key responses must be non-cacheable');
requireContract(str_contains($controller, 'userId === null'), 'The controller needs a defense-in-depth authenticated-user guard');

requireContract($capabilities !== false, 'Capabilities source is unreadable');
requireContract(!str_contains($capabilities, "'token' =>"), 'Capabilities must never contain a generated key');

$path = '/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token';
requireContract(isset($openApi['paths'][$path]['post']), 'OpenAPI must describe the POST endpoint');
requireContract(!isset($openApi['paths'][$path]['get']), 'OpenAPI must not advertise a GET endpoint');
$operation = $openApi['paths'][$path]['post'];
requireContract(($operation['parameters'][0]['name'] ?? null) === 'OCS-APIRequest', 'OpenAPI must require the OCS header');
requireContract(($operation['parameters'][0]['required'] ?? false) === true, 'The OCS header must be required');
requireContract(isset($operation['security']) && count($operation['security']) === 2, 'OpenAPI must require account authentication');
requireContract(($openApi['components']['schemas']['RecognizeToken']['properties']['token']['format'] ?? null) === 'password', 'OpenAPI must mark the key as sensitive');

fwrite(STDOUT, "Security contract: ok\n");

#!/usr/bin/env sh

set -eu

cd "$(dirname "$0")/.."

find appinfo lib tests -name '*.php' -print0 | xargs -0 -n1 php -l
xmllint --noout appinfo/info.xml
php -r 'json_decode(file_get_contents("openapi.json"), true, 512, JSON_THROW_ON_ERROR);'
php tests/VersionPolicyTest.php
php tests/SecurityContractTest.php

printf '%s\n' 'Obiente Native Bridge checks: ok'

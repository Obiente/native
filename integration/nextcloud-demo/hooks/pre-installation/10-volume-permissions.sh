#!/usr/bin/env sh
set -eu

chown -R www-data:www-data /var/www/html/config
find /var/www/html/config -type d -exec chmod u+rwx {} +

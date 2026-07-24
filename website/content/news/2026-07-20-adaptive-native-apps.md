---
title: Native interfaces for more than a fixed app list
slug: adaptive-native-apps
date: 2026-07-20
description: How verified contracts and reusable semantics can turn unfamiliar Nextcloud apps into useful native interfaces.
tags: adaptive apps, native UI, Nextcloud, architecture
---

# Native interfaces for more than a fixed app list

Most clients support a fixed set of server applications. Nextcloud Native is exploring
a broader approach: discover a verified contract, classify the data and actions it
exposes, then compose an interface from reusable native components.

The important constraint is that inference may improve presentation, but may never
invent an endpoint, payload, permission, or destructive action. Deterministic evidence
always wins.

That means a familiar data shape can receive a familiar experience. Records with
columns can become an editable table. Ordered cards can become a board. Dated entries
can become a calendar or timeline. Purpose-built adapters remain welcome where they
create a genuine improvement, but they are not the only route to a useful interface.

The architecture documentation describes the schema and safety boundaries, while the
compatibility matrix records which applications have been tested with real contracts.


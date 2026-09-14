category: fix
issue: none
pull: 445
platforms: android
user-facing: yes

Restore detected-media setup drafts, reclaim failed folder selections without disturbing open setup, and stop idle cleanup polling. Confirmed folder removals now report completion even when background permission cleanup must retry. Independent folder permission cleanup continues when one provider fails, and unavailable metadata no longer claims permissions were unchanged. Legacy shared roots can regain expired permissions through either verified account owner.

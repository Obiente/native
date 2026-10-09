category: fix
issue: 493
pull: none
platforms: android, desktop
user-facing: yes

Dynamic apps on Android no longer fail App Store contract discovery because a contract parser could not initialize. If contract code cannot start on a device, the app now shows an explained fallback instead of an internal class name.

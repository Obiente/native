category: feature
issue: 471
pull: none
platforms: linux, windows
user-facing: yes

Linux and Windows desktop now also trust certificate authorities from the operating system trust store, so servers using a private CA can sign in. Chain and hostname checks are unchanged.

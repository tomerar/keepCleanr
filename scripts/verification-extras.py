#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Re-adds checksums for platform-specific artifacts that `--write-verification-metadata` can't see
on Linux (aapt2 for macOS/Windows). Run after regenerating gradle/verification-metadata.xml."""
import hashlib, re, sys, urllib.request
p = 'gradle/verification-metadata.xml'
s = open(p).read()
m = re.search(r'<artifact name="aapt2-([^"]+)-linux\.jar">', s)
if not m:
    sys.exit('no linux aapt2 entry found')
ver = m.group(1)
for os_name in ('osx', 'windows'):
    name = f'aapt2-{ver}-{os_name}.jar'
    if name in s:
        continue
    url = f'https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/{ver}/{name}'
    sha = hashlib.sha256(urllib.request.urlopen(url).read()).hexdigest()
    anchor = m.group(0)
    i = s.index(anchor); j = s.index('</artifact>', i) + len('</artifact>')
    s = s[:j] + f'\n         <artifact name="{name}">\n            <sha256 value="{sha}" origin="Fetched from dl.google.com for non-Linux contributors"/>\n         </artifact>' + s[j:]
    print('added', name, sha)
open(p, 'w').write(s)

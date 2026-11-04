# SPDX-License-Identifier: GPL-3.0-or-later
# Reproducible builds (C199): R8 9.5's optimisation (argument propagation) gives a different classes.dex from run to
# run with the same inputs. Until a stable AGP ships an R8 with upstream's fix (r8 953faf495b), the release build is
# shrunk and obfuscated but not optimised. A conservative choice: no alpha compiler builds the code that deletes files.
-dontoptimize

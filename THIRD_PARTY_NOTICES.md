# Third-Party Notices

## Scrolless content-filter detection rules

Airlock's native feed selectors in `ContentDetectionPolicy.java` are adapted
from Scrolless, Copyright (C) 2026 Scrolless, licensed under
GNU General Public License version 3 or (at your option) any later version
(`GPL-3.0-or-later`). The Java implementation, lifecycle safety, settings and UI
are Airlock-specific; no Scrolless runtime dependency is included.

- Project: <https://github.com/duartebarbosadev/Scrolless>
- Inspected commit: `9bc0dd0edc022c7be2a8a9af1181064c6d73014c`
- Source: <https://github.com/duartebarbosadev/Scrolless/blob/9bc0dd0edc022c7be2a8a9af1181064c6d73014c/core/domain/src/main/java/com/scrolless/app/core/model/BlockableApp.kt>
- Visibility reference: <https://github.com/duartebarbosadev/Scrolless/blob/9bc0dd0edc022c7be2a8a9af1181064c6d73014c/app/src/main/java/com/scrolless/app/accessibility/ContentScanner.kt>

The adapted rules cover official YouTube, Instagram, Facebook and Snapchat
package names and narrowly identified screens. Airlock intentionally excludes
the generic Facebook video-layout fallback, other package variants and broad
video-player predicates. Source evidence does not establish device reliability.

The GPLv3 license text is in [`LICENSE`](LICENSE). Distribution must preserve
applicable notices and provide corresponding source under the GPL. This code
is provided without warranty, including implied warranties of merchantability
or fitness for a particular purpose.

## Material Symbols

The launcher and notification `lock_clock` artwork comes from Google's Material
Symbols, licensed under Apache License 2.0:
<https://github.com/google/material-design-icons>.
This notice does not change the existing artwork or its license.

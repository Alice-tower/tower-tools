# OpenJFX 21.0.12 license provenance

The six module directories here are copied byte-for-byte from the `legal/`
directory in the official Gluon OpenJFX 21.0.12 Windows x64 SDK. Only modules
actually present in TowerReader's portable output are retained; javafx.fxml
is not packaged.

SDK download: https://download2.gluonhq.com/openjfx/21.0.12/openjfx-21.0.12_windows-x64_bin-sdk.zip
Official download index: https://gluonhq.com/products/javafx/
Retrieved: 2026-10-01
SDK SHA-256: 78ab10816e71ed51808bc5853bb26f38cf35b550a4cec233dbc4fbf5a3e8574c

The Maven Windows JARs omit this legal directory. These texts cover OpenJFX
GPL version 2 with the Classpath exception, its assembly exception, and the
native third-party code documented by the same-version Windows SDK:

- graphics: IJG JPEG 10, Mesa 21.0.3.
- media: DirectShow samples, GLib 2.82.4, GStreamer 1.26.5, libffi 3.4.4.
- web: ICU 73.1, libxml2 2.15.2, libxslt 1.1.45, WebKit 623.1 and all notices
  inside its complete `webkit.md` (LGPL, MPL, Apache, Unicode and BSD families).

Preserve the full module directories and their texts when distributing.
Source repository: https://github.com/openjdk/jfx21u
Complete release source: https://codeload.github.com/openjdk/jfx21u/zip/refs/tags/21.0.12%2B3
Source archives and their exact provenance are documented in apps/tower-reader/DEPENDENCIES.md.
Binary redistribution must satisfy the applicable GPL/LGPL source availability
requirements in addition to retaining these notices. Do not describe license
text inclusion alone as fulfillment of the corresponding-source requirement.

The graphics Windows JAR additionally bundles Microsoft's MSVC/UCRT DLLs.
These are Microsoft's redistributable runtime components, separately licensed
from OpenJFX. They are not covered by GPL or the repository's MIT license.
See the dependency audit for the separate Microsoft redistribution references.

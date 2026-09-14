<div align="center">

<h1 align="center"> WebGPUViewer </h1>

[![Release build](https://img.shields.io/github/actions/workflow/status/mKonic/webgpuviewer/release.yml?labelColor=27303D&label=Release&labelColor=06599d&color=043b69)](https://github.com/mKonic/webgpuviewer/actions/workflows/release.yml)
[![Release](https://img.shields.io/github/v/release/mKonic/webgpuviewer.svg?maxAge=3600&label=Release&labelColor=06599d&color=043b69)](https://github.com/mKonic/webgpuviewer/releases/latest)
[![License: MIT](https://img.shields.io/github/license/mKonic/webgpuviewer?labelColor=27303D&color=0877d2)](/LICENSE)

<div align="left">

A GPU-backed image viewer for Android: tiled rendering, upscaling, colour tables, HDR and page
transitions, in paged and continuous form. This fork of
[mpreg-ca/webgpuviewer](https://github.com/mpreg-ca/webgpuviewer) carries the fixes
[Komikku](https://github.com/mKonic/komikku) needs ahead of them landing upstream, and proposes the
ones of general interest back.

## Use

Releases are attached to their tag rather than published to Maven Central, so they resolve through
an ivy repository over the release assets.

```kotlin
// settings.gradle.kts
exclusiveContent {
    forRepository {
        ivy("https://github.com/mKonic/webgpuviewer/releases/download") {
            patternLayout {
                ivy("v[revision]/ivy-[revision].xml")
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { ivyDescriptor() }
        }
    }
    filter { includeModule("ca.mpreg", "webgpuviewer") }
}

// build.gradle.kts
implementation("ca.mpreg:webgpuviewer:1.2.0")
```

The coordinates are upstream's, so pointing at a different repository is all it takes to build
against upstream instead.

## License

MIT, as upstream. See [LICENSE](./LICENSE).

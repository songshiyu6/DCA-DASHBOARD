# SA-07 Bundle Report

Date: 2026-08-28

## Baseline Measurement

Command:

```text
npm run build
```

Before the 7E change, the build completed successfully with Vite 7.3.6 and
2296 transformed modules. The measured output was:

| Asset | Size |
| --- | ---: |
| `index-DW4_HujC.js` | 548,509 bytes |
| `charts-NNFk3v_p.js` | 681,054 bytes |
| `index-DKPYQf5j.css` | 46,260 bytes |
| `PortfolioChart-CQCaIElY.js` | 2,535 bytes |
| `PriceChart-BYH5yM5F.js` | 1,816 bytes |

The build warned that the main and charts chunks exceeded 500 kB. The chart
libraries were already isolated by the existing `manualChunks` setting, and
the chart components were already lazy-loaded. The live entry chunk still
contained the demo fixture marker `dca-terminal-fixture-state`, because the
mode-compatible API facade statically imported the demo adapter.

## Decision

Keep the existing chart split and defer loading the demo adapter until a demo
API method is called. This preserves the synchronous, mode-compatible public
`api` object and its method signatures while removing demo fixtures from the
live initial chunk. The final build measurement is recorded below.

## Final Measurement

The live build after the change completed with the same 2296 transformed
modules:

| Asset | Size |
| --- | ---: |
| `index-CmBHl7rt.js` | 544,929 bytes |
| `charts-NNFk3v_p.js` | 681,054 bytes |
| `index-DKPYQf5j.css` | 46,260 bytes |
| `PortfolioChart-CXihP0XT.js` | 2,535 bytes |
| `PriceChart-CuZ-lD9-.js` | 1,816 bytes |

The live entry chunk decreased by 3,580 bytes, and the fixture state marker
`dca-terminal-fixture-state` is absent from the live assets. A demo-mode build
also completed and produced the deferred adapter chunk:

| Asset | Size |
| --- | ---: |
| `api-CnmUSQhX.js` | 23,514 bytes |
| `index-DmM5lGlX.js` | 532,444 bytes |
| `charts-NNFk3v_p.js` | 681,054 bytes |

No additional manual chunk was added. The existing chart split isolates the
largest third-party payload, while the measured API split removes demo code
from the live initial bundle without changing the public API contract.

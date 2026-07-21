# PC Builder App — REST API Specification (Spring Boot)

Base URL: `/api/v1`
All responses are JSON. All list endpoints that support pagination use Spring's standard `page`, `size`, and `sort` query params and return a paginated wrapper.

---

## 1. Home Screen

### 1.1 Hero Header Stats
**GET** `/home/stats`

Response:
```json
{
  "buildsCount": 1250,
  "partsCount": 8400,
  "storesCount": 32
}
```

### 1.2 Today's Deals
**GET** `/home/deals`

Response:
```json
[
  {
    "id": 101,
    "vendorName": "TechStore",
    "category": "GPU",
    "productName": "RTX 4070 Super",
    "productImage": "https://cdn.example.com/img/101.jpg",
    "originalPrice": 32000.00,
    "discountPrice": 27500.00,
    "discountPercentage": 14,
    "inStock": true
  }
]
```

### 1.3 Random Components
**GET** `/home/random-components?limit=10`

Response:
```json
[
  {
    "id": 55,
    "vendorName": "TechStore",
    "category": "CPU",
    "productName": "Ryzen 7 7800X3D",
    "productImage": "https://cdn.example.com/img/55.jpg",
    "price": 18500.00,
    "inStock": true
  }
]
```

### 1.4 Components (Home section)
**GET** `/home/components?limit=10`

Same response shape as 1.3.

### 1.5 Categories
**GET** `/categories`

Response:
```json
[
  {
    "id": 1,
    "name": "GPU",
    "componentsCount": 340,
    "icon": "https://cdn.example.com/icons/gpu.svg",
    "specs": ["VRAM", "Chipset", "TDP", "Length"],
    "brands": ["NVIDIA", "AMD", "ASUS", "MSI", "Gigabyte"]
  }
]
```

Suggested seeded categories: CPU, GPU, Motherboard, RAM, SSD, HDD, PSU, Case, Cooler, Fans, Monitor, Keyboard, Mouse, Headset.

### 1.6 Latest Hardware News
**GET** `/news?page=0&size=10`

Response item:
```json
{
  "id": 7,
  "image": "https://cdn.example.com/news/7.jpg",
  "companyName": "AMD",
  "title": "AMD announces new Zen 6 lineup",
  "description": "Short summary text...",
  "author": "John Doe",
  "articleUrl": "https://source-site.com/article"
}
```

---

## 2. Components Screen (Paginated + Search + Filters)

### 2.1 List / Search / Filter Components
**GET** `/components`

Query parameters:

| Param | Type | Description |
|---|---|---|
| `page` | int | Page number, default 0 |
| `size` | int | Page size, default 20 |
| `search` | string | Free-text search on product name |
| `category` | string / id | Filter by category |
| `brand` | string (repeatable) | e.g. `brand=ASUS&brand=MSI` |
| `minPrice` | decimal | Price range lower bound |
| `maxPrice` | decimal | Price range upper bound |
| `inStock` | boolean | Only in-stock items |
| `sort` | string | e.g. `price,asc`, `price,desc`, `name,asc` |
| `spec.<key>` | string | Dynamic spec filters, e.g. `spec.VRAM=16GB&spec.Socket=AM5` |
| `compatibleWithBuild` | long | Build id. Only return components compatible with **every** component already in that build (see §5). |
| `compatibleWithComponents` | long (repeatable) | Ad-hoc alternative to `compatibleWithBuild` for builds not yet saved, e.g. `compatibleWithComponents=55&compatibleWithComponents=101` |
| `compatibilityMode` | string | `RULE_BASED` (default) or `AI`. See §5.4. |

> Example: "show me all GPUs compatible with this build" →
> `GET /components?category=GPU&compatibleWithBuild=12`

Response (Spring `Page<T>` shape):
```json
{
  "content": [
    {
      "id": 55,
      "vendorName": "TechStore",
      "category": "GPU",
      "productName": "RTX 4070 Super",
      "productImage": "https://cdn.example.com/img/55.jpg",
      "price": 27500.00,
      "inStock": true,
      "specs": { "VRAM": "12GB", "Chipset": "NVIDIA" }
    }
  ],
  "totalElements": 340,
  "totalPages": 17,
  "number": 0,
  "size": 20
}
```

### 2.2 Advanced Search
**POST** `/components/search`

Use POST when the filter combination is too complex for query params (multiple specs, multiple categories).

Request:
```json
{
  "search": "rtx",
  "categories": ["GPU"],
  "brands": ["ASUS", "MSI"],
  "priceRange": { "min": 10000, "max": 35000 },
  "inStock": true,
  "specs": { "VRAM": ["12GB", "16GB"] },
  "compatibleWithBuild": 12,
  "sort": "price,asc",
  "page": 0,
  "size": 20
}
```

Response: same paginated shape as 2.1.

### 2.3 Component Details
**GET** `/components/{id}`

Full details of a single component: all specs, vendor info, pricing, stock, and compatibility-relevant attributes used by the rule engine (socket, form factor, wattage draw, dimensions, etc).

Response:
```json
{
  "id": 55,
  "vendorName": "TechStore",
  "category": "CPU",
  "productName": "Ryzen 7 7800X3D",
  "brand": "AMD",
  "productImage": "https://cdn.example.com/img/55.jpg",
  "gallery": ["https://cdn.example.com/img/55-1.jpg", "https://cdn.example.com/img/55-2.jpg"],
  "price": 18500.00,
  "inStock": true,
  "description": "Full marketing/description text...",
  "specs": {
    "Socket": "AM5",
    "Cores": "8",
    "Threads": "16",
    "BaseClock": "4.2GHz",
    "BoostClock": "5.0GHz",
    "TDP": "120W",
    "IntegratedGraphics": "No"
  },
  "compatibilityAttributes": {
    "socket": "AM5",
    "tdpWatts": 120
  },
  "priceHistory": [
    { "date": "2026-06-01", "price": 19200.00 },
    { "date": "2026-07-01", "price": 18500.00 }
  ],
  "vendorOffers": [
    { "vendorName": "TechStore", "price": 18500.00, "inStock": true, "url": "https://vendor.example.com/p/55" },
    { "vendorName": "PartsHub", "price": 18999.00, "inStock": false, "url": "https://partshub.example.com/p/55" }
  ]
}
```

`compatibilityAttributes` is category-dependent (see §5.1 for the full attribute matrix) and is primarily consumed by the compatibility engine rather than rendered directly in the UI.

---

## 3. Build Generator

Generates a build either **from scratch** or by **filling in the missing pieces** around components the user already picked. All generated components are guaranteed to pass the compatibility engine (§5) against each other.

**POST** `/builds/generate`

Request:
```json
{
  "budget": 45000,
  "purpose": ["Gaming", "Streaming"],
  "brandPreference": ["AMD", "ASUS"],
  "mode": "NEW",
  "existingComponentIds": [55, 101]
}
```

| Field | Type | Notes |
|---|---|---|
| `budget` | decimal | Total budget for the parts still to be generated (excludes cost of `existingComponentIds` when mode != NEW) |
| `purpose` | string[] | e.g. `Gaming`, `Streaming`, `Workstation`, `Budget`, `SFF` |
| `brandPreference` | string[] | Soft preference, not a hard filter |
| `mode` | enum | `NEW` — build entirely from scratch. `FILL_MISSING` — keep `existingComponentIds` as-is and only pick components for the categories not yet covered (each pick validated against `existingComponentIds`). `REPLACE` — regenerate the whole build but try to stay close in spec to `existingComponentIds` (e.g. same tier GPU). |
| `existingComponentIds` | long[] | Required when `mode` != `NEW`. Ignored (may be omitted) for `NEW`. |

Response:
```json
{
  "components": [ { "...componentObject": "as in 2.1" } ],
  "totalPrice": 44200.00,
  "compatibilityReport": {
    "compatible": true,
    "issues": [],
    "warnings": []
  }
}
```

**POST** `/builds` — save a generated/custom build (see §4).

---

## 4. My PCs (Saved Builds)

> Open question for backend: builds could be stored locally, in Firebase, or in this backend. If backend-stored, these endpoints apply (requires auth):

- **GET** `/builds` — list current user's builds
- **GET** `/builds/{id}` — build details (components + total price)
- **POST** `/builds` — save a build `{ "name": "...", "componentIds": [...] }`
- **DELETE** `/builds/{id}` — remove a build

---

## 5. Compatibility

Two ways to consume compatibility: check a **single candidate** against an existing build, or **filter a whole list** (§2.1's `compatibleWithBuild` param) to only components that would fit.

### 5.1 Rule-Based Attribute Matrix

The rule engine compares `compatibilityAttributes` (§2.3) across category pairs:

| Pair | Rule |
|---|---|
| CPU ↔ Motherboard | `socket` must match |
| RAM ↔ Motherboard | `ramType` (DDR4/DDR5) must match; `ramSlotsUsed` ≤ motherboard's `ramSlots` |
| RAM ↔ CPU | `ramType` must be in CPU's supported memory types |
| PSU ↔ whole build | sum of component `tdpWatts` (+ headroom, default 20%) ≤ PSU `wattage` |
| PSU ↔ GPU | PSU has required `pcieConnectors` for GPU's power connector spec |
| Case ↔ Motherboard | motherboard `formFactor` in case's supported `formFactors` |
| Case ↔ GPU | GPU `lengthMm` ≤ case `maxGpuLengthMm` |
| Case ↔ Cooler | cooler `heightMm` ≤ case `maxCoolerHeightMm` (air) or radiator size in case's supported radiator sizes (AIO) |
| Cooler ↔ CPU | CPU `socket` in cooler's supported `sockets` |

### 5.2 Check a Candidate Against a Build
**POST** `/compatibility/check`

Request (either reference a saved build or pass ad-hoc component ids):
```json
{
  "buildId": 12,
  "existingComponentIds": [55, 101],
  "candidateComponentId": 890,
  "mode": "RULE_BASED"
}
```
`buildId` and `existingComponentIds` are mutually exclusive — pass whichever the client has on hand (saved build vs. in-progress local build).

Response:
```json
{
  "compatible": false,
  "issues": [
    { "rule": "PSU_WATTAGE", "message": "Estimated draw 620W exceeds PSU capacity of 550W" }
  ],
  "warnings": [
    { "rule": "CASE_GPU_LENGTH", "message": "GPU length is within 5mm of case max — check clearance" }
  ]
}
```

### 5.3 Full Build Compatibility Report
**GET** `/builds/{id}/compatibility`

Runs every pairwise rule in §5.1 across all components currently in the build. Response shape matches §5.2 but aggregates issues/warnings across all pairs instead of one candidate.

### 5.4 Compatibility Mode
Both §5.2 and the `compatibleWithBuild` filter in §2.1 accept `compatibilityMode`:
- `RULE_BASED` (default, v1) — deterministic checks from §5.1.
- `AI` (future scope, see §6) — LLM-assisted check that can reason about soft/edge cases (e.g. "will this cooler physically clear these RAM heatsinks") beyond the fixed rule matrix. Falls back to `RULE_BASED` if unavailable.

---

## 6. AI Features *(future scope)*

- **POST** `/ai/chat` — chat with hardware assistant
- **POST** `/ai/build-generator` — AI-powered version of §3, same request/response shape plus natural-language `prompt` field
- **POST** `/ai/compare-builds` — compare two or more builds `{ "buildIds": [1, 2] }`
- **POST** `/ai/compatibility-check` — AI-assisted version of §5.2/§5.3 for nuanced cases the rule matrix can't express

---

## Notes

**Pagination**: return the standard Spring `Page` response so the frontend can rely on `totalPages` / `totalElements`.

**Compatibility**: v1 ships rule-based only (§5.1–5.3); the `compatibilityMode=AI` switch (§5.4, §6) is a drop-in upgrade path once the AI endpoints exist — no client-facing contract change needed.

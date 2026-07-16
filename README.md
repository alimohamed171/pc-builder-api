# PC Bundle Builder API

Spring Boot 3 / Java 17 REST API for a PC-parts store: JWT auth, paginated & filterable
product catalog (backed by your existing `products` table), and a bundle builder that
checks part compatibility and suggests alternatives when something doesn't fit.

## 1. Requirements

- Java 17+
- Maven 3.9+
- MySQL 8

## 2. Database setup

The `products` table already exists in your `pc_bundle_export.sql` dump (also copied
into `db/pc_bundle_export.sql` in this project for convenience). Import it first:

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS pc_bundle"
mysql -u root -p pc_bundle < db/pc_bundle_export.sql
```

The app will then auto-create the two extra tables it needs (`users`, `bundles`,
`bundle_items`) on startup via `spring.jpa.hibernate.ddl-auto=update` — it will **not**
touch your existing `products` table.

> Note: every row in the supplied dump has `active = 0`. The API deliberately does
> **not** filter on `active` (it uses `in_stock` instead), otherwise every endpoint
> would return empty results. Feel free to change this in `ProductRepository` /
> `CompatibilityService` once your import job starts setting `active` correctly.

## 3. Configure environment

```bash
cp .env .env
```

Edit `.env`:

```
DB_HOST=localhost
DB_PORT=3306
DB_NAME=pc_bundle
DB_USERNAME=root
DB_PASSWORD=your_mysql_password
JWT_SECRET=replace-with-a-long-random-secret-at-least-256-bits
```

`.env` is read automatically at startup via `spring-dotenv` — no extra setup needed.

## 4. Run

```bash
mvn spring-boot:run
```

API base URL: `http://localhost:8080`

## 5. Response shape

Every endpoint returns the same envelope:

```json
{
  "status": true,
  "message": "Human readable message",
  "data": { }
}
```

## 6. Auth

### Register
`POST /api/auth/register`
```json
{ "name": "Sara Ahmed", "email": "sara@example.com", "password": "secret123" }
```

### Login
`POST /api/auth/login`
```json
{ "email": "sara@example.com", "password": "secret123" }
```

Both return:
```json
{ "status": true, "message": "...", "data": { "token": "eyJ...", "tokenType": "Bearer", "user": {...} } }
```

Send the token on every protected request:
```
Authorization: Bearer <token>
```

Products endpoints (`GET /api/products/**`) are public; bundle endpoints require auth.

## 7. Products (home / search screens)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/products?category=CPU&page=0&size=20` | Paginated product list, category optional |
| GET | `/api/products/deals/random?category=GPU&limit=10` | Random deals, category optional |
| GET | `/api/products/search?category=GPU&minPrice=10000&maxPrice=50000&keyword=rtx&page=0&size=20` | Filtered search |
| GET | `/api/products/{id}` | Single product |

Valid categories: `CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY`

## 8. Bundles (requires `Authorization` header)

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/bundles` | Create a bundle |
| PUT | `/api/bundles/{id}` | Edit a bundle |
| GET | `/api/bundles/{id}` | Retrieve one bundle |
| GET | `/api/bundles?page=0&size=20` | List my bundles |

### Create/update body
```json
{
  "name": "My Gaming Rig",
  "items": [
    { "productId": 1, "quantity": 1 },
    { "productId": 34, "quantity": 1 },
    { "productId": 159, "quantity": 1 },
    { "productId": 195, "quantity": 1 }
  ]
}
```

### Response
The bundle is always saved. If parts don't work together, `data.compatible` is
`false`, `data.issues` explains why, and `data.alternatives` lists compatible
replacement products per category:

```json
{
  "status": true,
  "message": "Bundle saved, but it is NOT fully compatible: CPU socket (AM5) does not match motherboard socket (LGA1700). See 'alternatives' for compatible replacement options.",
  "data": {
    "id": 12,
    "name": "My Gaming Rig",
    "totalPrice": 45230.00,
    "compatible": false,
    "items": [ ... ],
    "issues": [
      { "category": "MOTHERBOARD", "reason": "CPU socket (AM5) does not match motherboard socket (LGA1700)." }
    ],
    "alternatives": {
      "MOTHERBOARD": [ { "id": 33, "name": "ASUS TUF GAMING X870-PLUS WIFI...", "price": 17500.00 } ],
      "CPU": [ { "id": 21, "name": "Intel Core i5 12400F...", "price": 8000.00 } ]
    }
  }
}
```

### Compatibility rules implemented
1. **CPU ↔ Motherboard**: socket must match.
2. **Motherboard ↔ Case**: motherboard form factor must physically fit the case.
3. **PSU wattage**: must cover an estimated CPU TDP + GPU draw + headroom (configurable
   via `PSU_HEADROOM_WATTS` / `DEFAULT_GPU_DRAW_WATTS` in `.env`).
4. **Cooler ↔ Case**: large AIO radiators (>240mm) are flagged in compact/Mini-ITX cases.

Rules are intentionally conservative: if the `specs` JSON is missing a field needed
for a check (common in scraped data), that check is skipped rather than producing a
false positive.

## 9. Project layout

```
src/main/java/com/pcbuilder
 ├── common/        ApiResponse, PageResponse, SpecsUtil (parent DTO + shared helpers)
 ├── config/         SecurityConfig
 ├── security/       JWT filter/service, UserPrincipal, UserDetailsService
 ├── exception/       Custom exceptions + GlobalExceptionHandler
 ├── auth/           entity / repository / dto / mapper / service / controller
 ├── product/         entity / repository / dto / mapper / service / controller
 └── bundle/          entity / repository / dto / mapper / service / controller
                      (+ CompatibilityService — the compatibility engine)
```

Mappers are kept as their own classes (`UserMapper`, `ProductMapper`, `BundleMapper`),
separate from services and controllers, as requested.

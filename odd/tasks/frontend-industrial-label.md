# Rediseño visual: Etiqueta industrial (frontend)

Rama: `feat/industrial-label` · Entrega: PR único si queda bajo ~400 líneas autoradas, si no cadena por unidad · Runner: `npm test` (Vitest).

## Specs

- **S1 — Alcance total.** Toda la app: vitrina, ficha de producto, carrito, checkout, confirmación, seguimiento de invitado, login, dashboard, pedidos, catálogo de operador, estados y shell.
- **S2 — Marca intacta.** "Pedidos360", la marca "P" y la frase «Mira primero. Después decides.» se conservan tal cual.
- **S3 — Contenido y flujo intactos.** Los 9 productos, los textos en español neutro, los estados de pedido y el flujo catálogo → carrito → login → confirmación no cambian.
- **S4 — Honestidad intacta.** Nada de reseñas, envíos, cuotas, descuentos ni «pagado». El rediseño no agrega ninguna promesa nueva.
- **S5 — Mundo visual.** Algodón `#f2f2ee`, nailon `#0d0d0d`, naranja de seguridad `#ff5c00`; Barlow y Barlow Condensed auto-hospedadas; esquinas rectas, reglas de 1px, franjas de peligro a 45°, comillas rectas alrededor de los nombres de zona y etiqueta colgante con muesca como objeto firma.
- **S6 — Pruebas y build.** `npm test` en verde sin cambiar textos accesibles; `npm run build` compila.
- **S7 — Responsive.** 1440 px y 390 px sin desborde horizontal; capturas en `.impeccable/review/`.
- **S8 — Sin fuentes externas.** Ningún `@import` ni `fonts.googleapis.com`; las caras se sirven desde `public/fonts/`.

## Tasks

| ID | Specs | Ruta | Estado |
| --- | --- | --- | --- |
| T1 | S5, S8 | inline | tokens, `@font-face`, base, botones, shell, formularios, tablas y login en `src/styles.css` |
| T2 | S5, S2 | inline | vitrina: `StoreLayout`, `StorePage` (etiqueta colgante), `Showcase` |
| T3 | S5, S3 | inline | catálogo: `CatalogShelf`, `ProductCard`, `ProductPlate`, `PriceStock`, `QuantityStepper` |
| T4 | S5, S3 | inline | compra: `ProductPage`, `CartPage`, `CheckoutPage`, `GuestTrackingPage` |
| T5 | S6 | inline | `npm test` + `npm run build` |
| T6 | S7 | inline | capturas 1440/390, `impeccable detect`, revisión final | done |
| T7 | S5 | inline | documentación: DESIGN.md y `.impeccable/design.json` | done |

## Log

**L1 (solicitud original, verbatim):**
> ahora quiero cambiar el diseño de la paguina por algo mas profesional y moderno

Respuestas de alcance: superficie «Toda la app, sin excepción»; sobrevive «Marca y frase, Contenido y flujo, Restricciones de honestidad»; problemas actuales «Se ve anticuada, Jerarquía y tipografía, No se siente confiable».

**L2:** dirección elegida en la página de decisión: `challenger-streetwear` = «Etiqueta industrial», build path `code` (sin comps medidos). Contrato de dirección en `.impeccable/surfaces/src-features-store-storepage-tsx.md`.

**L3:** fuentes Barlow descargadas a `public/fonts/` (6 woff2, subconjunto latin).

**L4:** lote de finish-review (craft-floor bans) aplicado y verificado:
- `.shell-alert`: border-left 4px → cuadrado naranja ::before (0.55rem).
- `.security-note`: border-left 3px removido.
- `.alert`: border-left 4px → cuadrado lleno ::before (0.55rem) sobre currentColor; `.alert--info/--success` sin `border-left-color`.
- `.selected-product`: border-left 3px removido.
- `.store-tracking__save`: border-left 6px → `h2::before` cuadrado naranja (0.55rem).
- `.store-tracking__reason`: border-left 4px → cuadrado lleno ::before (0.45rem) sobre currentColor.
- `.store-link-request__done`: border-left 4px → cuadrado lleno ::before (0.5rem) sobre currentColor.
- `.store-checkout__error`: border-left 4px → cuadrado lleno ::before (0.5rem) sobre currentColor.
- Kicker "Catálogo abierto" removido en `StorePage.tsx`.
- Kickers "Una vista clara de tu operación" y "Acceso seguro" removidos en `LoginPage.tsx`.
- `.store-card:hover`: sombra dura `6px 6px 0 nailon` → `translateY(-2px) + border-color nailon`.
- `::placeholder` color `--ink-faint` (opacity 1) en `src/styles.css`.
- Verificación: `npm test` 163/163 (26 files), `npm run build` compila (CSS 51.32 kB), `impeccable detect --json` → `[]`, capturas recapturadas (desktop, mobile, login-desktop, catalogo-error, seguimiento-desktop).

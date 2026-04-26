# Multi-stage build for the limit-order frontend.
#   - Stage 1 ("build"): node:20-alpine; runs `npm ci` then a production
#     Angular build. Output lives at /build/dist/frontend/browser/.
#   - Stage 2 ("runtime"): nginx:alpine; serves the static bundle with
#     SPA routing + gzip per `nginx.conf`.
#
# Both base images are multi-arch (linux/amd64 + linux/arm64) so the
# image builds natively on Apple Silicon and CI runners alike.

# ---------- build stage ----------

FROM node:20-alpine AS build

WORKDIR /build

# Lockfile-first ordering means changing src/ doesn't invalidate the
# `npm ci` layer — saves ~200 MB of re-download per rebuild.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Angular 21's `defaultConfiguration: "production"` (in angular.json)
# means `ng build` already implies --configuration=production, but
# spelling it out matches the plan's literal acceptance wording.
RUN npx ng build --configuration=production

# ---------- runtime stage ----------

# `alpine-slim` (vs full `alpine`) drops perl + GeoIP + image-filter +
# xslt + njs dynamic modules — none of which a static-SPA server needs
# — and saves ~37 MB. Keeps the final image under the plan's ~50 MB budget.
FROM nginx:alpine-slim AS runtime

# Replace the default server block with our SPA-aware config.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the Angular build output. Angular 21 emits the browser bundle
# to `dist/<project>/browser/`; our project name is `frontend`.
COPY --from=build /build/dist/frontend/browser/ /usr/share/nginx/html/

EXPOSE 80

# Default `nginx -g "daemon off;"` from the base image — no override.
